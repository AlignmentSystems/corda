/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.services.config

import net.corda.nodeapi.internal.config.SSLConfiguration
import java.nio.file.Path
import java.nio.file.Paths

data class SslOptions(override val certificatesDirectory: Path, override val keyStorePassword: String, override val trustStorePassword: String) : SSLConfiguration {
    constructor(certificatesDirectory: String, keyStorePassword: String, trustStorePassword: String) : this(certificatesDirectory.toAbsolutePath(), keyStorePassword, trustStorePassword)

    fun copy(certificatesDirectory: String = this.certificatesDirectory.toString(), keyStorePassword: String = this.keyStorePassword, trustStorePassword: String = this.trustStorePassword): SslOptions = copy(certificatesDirectory = certificatesDirectory.toAbsolutePath(), keyStorePassword = keyStorePassword, trustStorePassword = trustStorePassword)
}

private fun String.toAbsolutePath() = Paths.get(this).toAbsolutePath()