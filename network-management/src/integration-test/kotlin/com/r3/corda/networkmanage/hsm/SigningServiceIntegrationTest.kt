package com.r3.corda.networkmanage.hsm

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import com.r3.corda.networkmanage.common.persistence.configureDatabase
import com.r3.corda.networkmanage.common.utils.buildCertPath
import com.r3.corda.networkmanage.common.utils.toX509Certificate
import com.r3.corda.networkmanage.doorman.startDoorman
import com.r3.corda.networkmanage.hsm.persistence.ApprovedCertificateRequestData
import com.r3.corda.networkmanage.hsm.persistence.DBSignedCertificateRequestStorage
import com.r3.corda.networkmanage.hsm.persistence.SignedCertificateRequestStorage
import com.r3.corda.networkmanage.hsm.signer.HsmCsrSigner
import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.uncheckedCast
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.node.utilities.registration.HTTPNetworkRegistrationService
import net.corda.node.utilities.registration.NetworkRegistrationHelper
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.*
import net.corda.testing.common.internal.testNetworkParameters
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.h2.tools.Server
import org.junit.*
import org.junit.rules.TemporaryFolder
import java.net.URL
import java.util.*
import javax.persistence.PersistenceException
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.concurrent.thread

class SigningServiceIntegrationTest {
    companion object {
        val H2_TCP_PORT = "8092"
        val HOST = "localhost"
        val DB_NAME = "test_db"
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)

    private lateinit var timer: Timer

    @Before
    fun setUp() {
        timer = Timer()
    }

    @After
    fun tearDown() {
        timer.cancel()
    }

    private fun givenSignerSigningAllRequests(storage: SignedCertificateRequestStorage): HsmCsrSigner {
        // Create all certificates
        val rootCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val rootCACert = X509Utilities.createSelfSignedCACertificate(CordaX500Name(commonName = "Integration Test Corda Node Root CA",
                organisation = "R3 Ltd", locality = "London", country = "GB"), rootCAKey)
        val intermediateCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val intermediateCACert = X509Utilities.createCertificate(CertificateType.INTERMEDIATE_CA, rootCACert, rootCAKey,
                CordaX500Name(commonName = "Integration Test Corda Node Intermediate CA", locality = "London", country = "GB",
                        organisation = "R3 Ltd"), intermediateCAKey.public)
        // Mock signing logic but keep certificate persistence
        return mock {
            on { sign(any()) }.then {
                val toSign: List<ApprovedCertificateRequestData> = uncheckedCast(it.arguments[0])
                toSign.forEach {
                    JcaPKCS10CertificationRequest(it.request).run {
                        val certificate = X509Utilities.createCertificate(CertificateType.TLS, intermediateCACert, intermediateCAKey, subject, publicKey).toX509Certificate()
                        it.certPath = buildCertPath(certificate, rootCACert.toX509Certificate())
                    }
                }
                storage.store(toSign, listOf("TEST"))
            }
        }
    }

    @Test
    fun `Signing service signs approved CSRs`() {
        //Start doorman server
        val database = configureDatabase(makeTestDataSourceProperties())
        val doorman = startDoorman(NetworkHostAndPort(HOST, 0), database, approveAll = true, approveInterval = 2, signInterval = 30, networkMapParameters = testNetworkParameters(emptyList()))

        // Start Corda network registration.
        val config = testNodeConfiguration(
                baseDirectory = tempFolder.root.toPath(),
                myLegalName = ALICE.name).also {
            val doormanHostAndPort = doorman.hostAndPort
            whenever(it.compatibilityZoneURL).thenReturn(URL("http://${doormanHostAndPort.host}:${doormanHostAndPort.port}"))
        }

        val signingServiceStorage = DBSignedCertificateRequestStorage(configureDatabase(makeTestDataSourceProperties()))

        val hsmSigner = givenSignerSigningAllRequests(signingServiceStorage)
        // Poll the database for approved requests
        timer.scheduleAtFixedRate(0, 1.seconds.toMillis()) {
            // The purpose of this tests is to validate the communication between this service and Doorman
            // by the means of data in the shared database.
            // Therefore the HSM interaction logic is mocked here.
            try {
                val approved = signingServiceStorage.getApprovedRequests()
                if (approved.isNotEmpty()) {
                    hsmSigner.sign(approved)
                    timer.cancel()
                }
            } catch (exception: PersistenceException) {
                // It may happen that Doorman DB is not created at the moment when the signing service polls it.
                // This is due to the fact that schema is initialized at the time first hibernate session is established.
                // Since Doorman does this at the time the first CSR arrives, which in turn happens after signing service
                // startup, the very first iteration of the signing service polling fails with
                // [org.hibernate.tool.schema.spi.SchemaManagementException] being thrown as the schema is missing.
            }
        }
        NetworkRegistrationHelper(config, HTTPNetworkRegistrationService(config.compatibilityZoneURL!!)).buildKeystore()
        verify(hsmSigner).sign(any())
        doorman.close()
    }

    /*
     * Piece of code is purely for demo purposes and should not be considered as actual test (therefore it is ignored).
     * Its purpose is to produce 3 CSRs and wait (polling Doorman) for external signature.
     * The use of the jUnit testing framework was chosen due to the convenience reasons: mocking, tempFolder storage.
     * It is meant to be run together with the [DemoMain.main] method, which executes HSM signing service.
     * The split is done due to the limited console support while executing tests and inability to capture user's input there.
     *
     */
    @Test
    @Ignore
    fun `DEMO - Create CSR and poll`() {
        //Start doorman server
        val database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig())
        val doorman = startDoorman(NetworkHostAndPort(HOST, 0), database, approveAll = true, approveInterval = 2, signInterval = 10, networkMapParameters = testNetworkParameters(emptyList()))

        thread(start = true, isDaemon = true) {
            val h2ServerArgs = arrayOf("-tcpPort", H2_TCP_PORT, "-tcpAllowOthers")
            Server.createTcpServer(*h2ServerArgs).start()
        }

        // Start Corda network registration.
        (1..3).map {
            thread(start = true) {

                val config = testNodeConfiguration(
                        baseDirectory = tempFolder.root.toPath(),
                        myLegalName = when (it) {
                            1 -> ALICE.name
                            2 -> BOB.name
                            3 -> CHARLIE.name
                            else -> throw IllegalArgumentException("Unrecognised option")
                        }).also {
                    whenever(it.compatibilityZoneURL).thenReturn(URL("http://$HOST:${doorman.hostAndPort.port}"))
                }
                NetworkRegistrationHelper(config, HTTPNetworkRegistrationService(config.compatibilityZoneURL!!)).buildKeystore()
            }
        }.map { it.join() }
        doorman.close()
    }
}

private fun makeTestDataSourceProperties(): Properties {
    val props = Properties()
    props.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
    props.setProperty("dataSource.url", "jdbc:h2:mem:${SigningServiceIntegrationTest.DB_NAME};DB_CLOSE_DELAY=-1")
    props.setProperty("dataSource.user", "sa")
    props.setProperty("dataSource.password", "")
    return props
}

internal fun makeNotInitialisingTestDatabaseProperties() = DatabaseConfig(initialiseSchema = false)