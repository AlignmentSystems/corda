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

import net.corda.core.crypto.SecureHash
import net.corda.nodeapi.internal.SignedNodeInfo
import org.hibernate.annotations.UpdateTimestamp
import java.io.Serializable
import java.time.Instant
import javax.persistence.*

@Entity
@Table(name = "node_info")
data class NodeInfoEntity(
        // Hash of serialized [NodeInfo] without signatures.
        @Id
        @Column(name = "node_info_hash", length = 64)
        // AttributeConverters can't be used on @Id attributes, otherwise this would be SecureHash
        val nodeInfoHash: String,

        @Column(name = "public_key_hash", length = 64)
        @Convert(converter = SecureHashAttributeConverter::class)
        val publicKeyHash: SecureHash,

        @ManyToOne(optional = false, fetch = FetchType.LAZY)
        @JoinColumn(name = "cert_signing_request", nullable = false)
        val certificateSigningRequest: CertificateSigningRequestEntity,

        @Lob
        @Column(name = "signed_node_info_bytes", nullable = false)
        @Convert(converter = SignedNodeInfoConverter::class)
        val signedNodeInfo: SignedNodeInfo,

        @Column(name = "is_current", nullable = false)
        val isCurrent: Boolean,

        @Column(name = "published_at", nullable = false)
        @UpdateTimestamp
        val publishedAt: Instant = Instant.now(),

        @ManyToOne(fetch = FetchType.EAGER)
        @JoinColumn(name = "accepted_params_update")
        val acceptedParametersUpdate: ParametersUpdateEntity?
) : Serializable
