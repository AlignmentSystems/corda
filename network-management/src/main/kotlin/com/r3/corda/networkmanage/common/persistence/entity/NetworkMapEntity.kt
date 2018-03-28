/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package com.r3.corda.networkmanage.common.persistence.entity

import net.corda.core.internal.DigitalSignatureWithCert
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.SignedNetworkMap
import java.io.Serializable
import java.security.cert.X509Certificate
import javax.persistence.*

@Entity
@Table(name = "network_map")
class NetworkMapEntity(
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        val version: Long? = null,

        @Lob
        @Column(name = "serialized_network_map", nullable = false)
        @Convert(converter = NetworkMapConverter::class)
        val networkMap: NetworkMap,

        @Lob
        @Column(name = "signature", nullable = false)
        val signature: ByteArray,

        @Lob
        @Column(name = "cert", nullable = false)
        @Convert(converter = X509CertificateConverter::class)
        val certificate: X509Certificate,

        @ManyToOne(optional = false, fetch = FetchType.EAGER)
        @JoinColumn(name = "network_parameters", nullable = false)
        val networkParameters: NetworkParametersEntity
) : Serializable {
    fun toSignedNetworkMap(): SignedNetworkMap {
        return SignedNetworkMap(networkMap.serialize(), DigitalSignatureWithCert(certificate, signature))
    }
}
