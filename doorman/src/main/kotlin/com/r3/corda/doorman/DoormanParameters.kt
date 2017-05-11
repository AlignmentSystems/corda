package com.r3.corda.doorman

import com.r3.corda.doorman.OptionParserHelper.toConfigWithOptions
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.div
import net.corda.node.utilities.getPath
import net.corda.nodeapi.config.parseAs
import java.nio.file.Path
import java.util.*

data class DoormanParameters(val basedir: Path,
                             val keystorePassword: String?,
                             val caPrivateKeyPassword: String?,
                             val rootKeystorePassword: String?,
                             val rootPrivateKeyPassword: String?,
                             val host: String,
                             val port: Int,
                             val dataSourceProperties: Properties,
                             val keygen: Boolean = false,
                             val rootKeygen: Boolean = false,
                             val jiraConfig: JiraConfig? = null,
                             val keystorePath: Path = basedir / "certificates" / "caKeystore.jks",
                             val rootStorePath: Path = basedir / "certificates" / "rootCAKeystore.jks"
) {
    val mode = if (rootKeygen) Mode.ROOT_KEYGEN else if (keygen) Mode.CA_KEYGEN else Mode.DOORMAN

    enum class Mode {
        DOORMAN, CA_KEYGEN, ROOT_KEYGEN
    }

    data class JiraConfig(
            val address: String,
            val projectCode: String,
            val username: String,
            val password: String,
            val doneTransitionCode: Int
    )
}

fun parseParameters(vararg args: String): DoormanParameters {
    val argConfig = args.toConfigWithOptions {
        accepts("basedir", "Overriding configuration filepath, default to current directory.").withRequiredArg().defaultsTo(".").describedAs("filepath")
        accepts("configFile", "Overriding configuration file, default to <<current directory>>/node.conf.").withRequiredArg().describedAs("filepath")
        accepts("keygen", "Generate CA keypair and certificate using provide Root CA key.").withOptionalArg()
        accepts("rootKeygen", "Generate Root CA keypair and certificate.").withOptionalArg()
        accepts("keystorePath", "CA keystore filepath, default to [basedir]/certificates/caKeystore.jks.").withRequiredArg().describedAs("filepath")
        accepts("rootStorePath", "Root CA keystore filepath, default to [basedir]/certificates/rootCAKeystore.jks.").withRequiredArg().describedAs("filepath")
        accepts("keystorePassword", "CA keystore password.").withRequiredArg().describedAs("password")
        accepts("caPrivateKeyPassword", "CA private key password.").withRequiredArg().describedAs("password")
        accepts("rootKeystorePassword", "Root CA keystore password.").withRequiredArg().describedAs("password")
        accepts("rootPrivateKeyPassword", "Root private key password.").withRequiredArg().describedAs("password")
        accepts("host", "Doorman web service host override").withRequiredArg().describedAs("hostname")
        accepts("port", "Doorman web service port override").withRequiredArg().ofType(Int::class.java).describedAs("port number")
    }

    val configFile = if (argConfig.hasPath("configFile")) {
        argConfig.getPath("configFile")
    } else {
        argConfig.getPath("basedir") / "node.conf"
    }
    val config = argConfig.withFallback(ConfigFactory.parseFile(configFile.toFile(), ConfigParseOptions.defaults().setAllowMissing(true))).resolve()
    return config.parseAs<DoormanParameters>()
}
