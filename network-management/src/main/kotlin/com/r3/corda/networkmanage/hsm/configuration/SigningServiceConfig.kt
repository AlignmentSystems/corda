/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package com.r3.corda.networkmanage.hsm.configuration

import com.google.common.primitives.Booleans
import com.r3.corda.networkmanage.common.utils.ShowHelpException
import com.r3.corda.networkmanage.hsm.authentication.AuthMode
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import joptsimple.OptionParser
import joptsimple.util.PathConverter
import joptsimple.util.PathProperties
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import java.net.InetAddress
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * Configuration parameters. Those are general configuration parameters shared with both
 * network map and certificate signing requests processes.
 */
data class SigningServiceConfig(val dataSourceProperties: Properties,
                                val database: DatabaseConfig = DatabaseConfig(),
                                val device: String,
                                val keySpecifier: Int,
                                val networkMap: NetworkMapCertificateConfig? = null,
                                val doorman: DoormanCertificateConfig? = null) {
    init {
        require(Booleans.countTrue(doorman != null, networkMap != null) == 1) {
            "Exactly one networkMap or doorman configuration needs to be specified."
        }
    }
}

/**
 * Network map signing process specific parameters.
 */
data class NetworkMapCertificateConfig(val username: String,
                                       val keyGroup: String,
                                       val authParameters: AuthParametersConfig)

/**
 * Certificate signing requests process specific parameters.
 */
data class DoormanCertificateConfig(val crlDistributionPoint: URL,
                                    val crlServerSocketAddress: NetworkHostAndPort,
                                    val crlUpdatePeriod: Long,
                                    val keyGroup:String,
                                    val validDays: Int,
                                    val rootKeyStoreFile: Path,
                                    val rootKeyStorePassword: String,
                                    val authParameters: AuthParametersConfig) {
    fun loadRootKeyStore(createNew: Boolean = false): X509KeyStore {
        return X509KeyStore.fromFile(rootKeyStoreFile, rootKeyStorePassword, createNew)
    }
}

/**
 * Authentication related parameters.
 */
data class AuthParametersConfig(val mode: AuthMode,
                                val password: String? = null, // This is either HSM password or key file password, depending on the mode.
                                val keyFilePath: Path? = null,
                                val threshold: Int)

class SigningServiceArgsParser {
    private val optionParser = OptionParser()
    private val helpOption = optionParser.acceptsAll(listOf("h", "help"), "show help").forHelp()
    private val baseDirArg = optionParser
            .accepts("basedir", "Overriding configuration filepath, default to current directory.")
            .withRequiredArg()
            .withValuesConvertedBy(PathConverter(PathProperties.DIRECTORY_EXISTING))
            .defaultsTo(Paths.get("."))
    private val configFileArg = optionParser
            .accepts("config-file", "The path to the config file")
            .withRequiredArg()
            .withValuesConvertedBy(PathConverter(PathProperties.FILE_EXISTING))

    fun parse(vararg args: String): SigningServiceCmdLineOptions {
        val optionSet = optionParser.parse(*args)
        if (optionSet.has(helpOption)) {
            throw ShowHelpException(optionParser)
        }
        val baseDir = optionSet.valueOf(baseDirArg)
        val configFile = optionSet.valueOf(configFileArg) ?: baseDir / "signing_service.conf"
        return SigningServiceCmdLineOptions(baseDir, configFile)
    }
}

data class SigningServiceCmdLineOptions(val baseDir: Path, val configFile: Path)
