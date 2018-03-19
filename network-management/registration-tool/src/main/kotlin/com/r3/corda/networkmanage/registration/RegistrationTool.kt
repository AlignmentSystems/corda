/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package com.r3.corda.networkmanage.registration

import com.r3.corda.networkmanage.registration.ToolOption.RegistrationOption
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.CertRole
import net.corda.core.internal.div
import net.corda.node.utilities.registration.HTTPNetworkRegistrationService
import net.corda.node.utilities.registration.NetworkRegistrationHelper
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.config.parseAs
import java.net.URL
import java.nio.file.Path

fun RegistrationOption.runRegistration() {
    val config = ConfigFactory.parseFile(configFile.toFile(), ConfigParseOptions.defaults().setAllowMissing(false))
            .resolve()
            .parseAs<RegistrationConfig>()

    val sslConfig = object : SSLConfiguration {
        override val keyStorePassword: String  by lazy { config.keyStorePassword ?: readPassword("Node Keystore password:") }
        override val trustStorePassword: String by lazy { config.trustStorePassword ?: readPassword("Node TrustStore password:") }
        override val certificatesDirectory: Path = configFile.parent / "certificates"
    }

    NetworkRegistrationHelper(sslConfig,
            config.legalName,
            config.email,
            HTTPNetworkRegistrationService(config.compatibilityZoneURL),
            config.networkRootTrustStorePath,
            config.networkRootTrustStorePassword ?: readPassword("Network trust root password:"), config.certRole).buildKeystore()
}

data class RegistrationConfig(val legalName: CordaX500Name,
                              val email: String,
                              val compatibilityZoneURL: URL,
                              val networkRootTrustStorePath: Path,
                              val certRole: CertRole,
                              val keyStorePassword: String?,
                              val networkRootTrustStorePassword: String?,
                              val trustStorePassword: String?)
