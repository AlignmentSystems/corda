/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package com.r3.corda.networkmanage.common.persistence

import com.r3.corda.networkmanage.TestBase
import com.r3.corda.networkmanage.common.persistence.entity.NodeInfoEntity
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.random63BitValue
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.createDevNetworkMapCa
import net.corda.nodeapi.internal.crypto.CertificateAndKeyPair
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.NetworkMapAndSigned
import net.corda.nodeapi.internal.network.ParametersUpdate
import net.corda.nodeapi.internal.network.verifiedNetworkMapCert
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.internal.createDevIntermediateCaCertPath
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.cert.X509Certificate
import java.time.Instant

class PersistentNetworkMapStorageTest : TestBase() {
    private lateinit var persistence: CordaPersistence
    private lateinit var networkMapStorage: PersistentNetworkMapStorage
    private lateinit var nodeInfoStorage: PersistentNodeInfoStorage
    private lateinit var requestStorage: PersistentCertificateSigningRequestStorage

    private lateinit var rootCaCert: X509Certificate
    private lateinit var networkMapCertAndKeyPair: CertificateAndKeyPair

    @Before
    fun startDb() {
        val (rootCa) = createDevIntermediateCaCertPath()
        rootCaCert = rootCa.certificate
        networkMapCertAndKeyPair = createDevNetworkMapCa(rootCa)
        persistence = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(runMigration = true))
        networkMapStorage = PersistentNetworkMapStorage(persistence)
        nodeInfoStorage = PersistentNodeInfoStorage(persistence)
        requestStorage = PersistentCertificateSigningRequestStorage(persistence)
    }

    @After
    fun closeDb() {
        persistence.close()
    }

    @Test
    fun `create active network map`() {
        // given
        // Create node info.
        val (signedNodeInfo) = createValidSignedNodeInfo("Test", requestStorage)
        val nodeInfoHash = nodeInfoStorage.putNodeInfo(signedNodeInfo)

        // Create network parameters
        val networkParameters = testNetworkParameters(maxTransactionSize = 1234567)
        val networkParametersSig = networkMapCertAndKeyPair.sign(networkParameters).sig
        val networkParametersHash = networkMapStorage.saveNetworkParameters(networkParameters, networkParametersSig)
        val networkMap = NetworkMap(listOf(nodeInfoHash), networkParametersHash, null)
        val networkMapAndSigned = NetworkMapAndSigned(networkMap) { networkMapCertAndKeyPair.sign(networkMap).sig }

        // when
        networkMapStorage.saveNewActiveNetworkMap(networkMapAndSigned)

        // then
        val activeNetworkMapEntity = networkMapStorage.getActiveNetworkMap()!!
        val activeSignedNetworkMap = activeNetworkMapEntity.toSignedNetworkMap()
        val activeNetworkMap = activeSignedNetworkMap.verifiedNetworkMapCert(rootCaCert)
        val activeNetworkParametersEntity = activeNetworkMapEntity.networkParameters
        val activeSignedNetworkParameters = activeNetworkParametersEntity.toSignedNetworkParameters()
        val activeNetworkParameters = activeSignedNetworkParameters.verifiedNetworkMapCert(rootCaCert)

        assertThat(activeNetworkMap).isEqualTo(networkMap)
        assertThat(activeSignedNetworkMap.sig).isEqualTo(networkMapAndSigned.signed.sig)
        assertThat(activeNetworkParameters).isEqualTo(networkParameters)
        assertThat(activeSignedNetworkParameters.sig).isEqualTo(networkParametersSig)
        assertThat(SecureHash.parse(activeNetworkParametersEntity.hash))
                .isEqualTo(activeNetworkMap.networkParameterHash)
                .isEqualTo(networkParametersHash)
    }

    @Test
    fun `getLatestNetworkParameters returns last inserted`() {
        val params1 = testNetworkParameters(minimumPlatformVersion = 1)
        val params2 = testNetworkParameters(minimumPlatformVersion = 2)
        networkMapStorage.saveNetworkParameters(params1, networkMapCertAndKeyPair.sign(params1).sig)
        // We may have not signed them yet.
        networkMapStorage.saveNetworkParameters(params2, null)

        assertThat(networkMapStorage.getLatestNetworkParameters()?.networkParameters).isEqualTo(params2)
    }

    @Test
    fun `getValidNodeInfoHashes returns only for current node-infos`() {
        // given
        // Create node infos.
        val (signedNodeInfoA) = createValidSignedNodeInfo("TestA", requestStorage)
        val (signedNodeInfoB) = createValidSignedNodeInfo("TestB", requestStorage)

        // Put signed node info data
        val nodeInfoHashA = nodeInfoStorage.putNodeInfo(signedNodeInfoA)
        val nodeInfoHashB = nodeInfoStorage.putNodeInfo(signedNodeInfoB)

        persistence.transaction {
            val entity = session.find(NodeInfoEntity::class.java, nodeInfoHashA.toString())
            session.merge(entity.copy(isCurrent = false))
        }

        // when
        val validNodeInfoHashes = networkMapStorage.getActiveNodeInfoHashes()

        // then
        assertThat(validNodeInfoHashes).containsOnly(nodeInfoHashB)
    }

    @Test
    fun `saveNewParametersUpdate clears the previous updates from database`() {
        val testParameters1 = testNetworkParameters(epoch = 1)
        val testParameters2 = testNetworkParameters(epoch = 2)
        val hash1 = testParameters1.serialize().hash
        val hash2 = testParameters2.serialize().hash
        val updateDeadline1 = Instant.ofEpochMilli(random63BitValue())
        val updateDeadline2 = Instant.ofEpochMilli(random63BitValue())
        networkMapStorage.saveNewParametersUpdate(testParameters1, "Update 1", updateDeadline1)
        networkMapStorage.saveNewParametersUpdate(testParameters1, "Update of update", updateDeadline1)
        assertThat(networkMapStorage.getParametersUpdate()?.toParametersUpdate()).isEqualTo(ParametersUpdate(hash1, "Update of update", updateDeadline1))
        networkMapStorage.saveNewParametersUpdate(testParameters2, "Update 3", updateDeadline2)
        assertThat(networkMapStorage.getParametersUpdate()?.toParametersUpdate()).isEqualTo(ParametersUpdate(hash2, "Update 3", updateDeadline2))
    }

    @Test
    fun `clear parameters update removes all parameters updates`() {
        val params1 = testNetworkParameters(minimumPlatformVersion = 1)
        val params2 = testNetworkParameters(minimumPlatformVersion = 2)
        networkMapStorage.saveNewParametersUpdate(params1, "Update 1", Instant.ofEpochMilli(random63BitValue()))
        networkMapStorage.saveNewParametersUpdate(params2, "Update 2", Instant.ofEpochMilli(random63BitValue()))
        networkMapStorage.clearParametersUpdates()
        assertThat(networkMapStorage.getParametersUpdate()).isNull()
    }
}
