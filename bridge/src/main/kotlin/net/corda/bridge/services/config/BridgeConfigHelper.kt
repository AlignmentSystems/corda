package net.corda.bridge.services.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigFactory.systemEnvironment
import com.typesafe.config.ConfigFactory.systemProperties
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import net.corda.core.internal.div
import net.corda.nodeapi.internal.config.toProperties
import org.slf4j.LoggerFactory
import java.nio.file.Path


fun configOf(vararg pairs: Pair<String, Any?>): Config = ConfigFactory.parseMap(mapOf(*pairs))
operator fun Config.plus(overrides: Map<String, Any?>): Config = ConfigFactory.parseMap(overrides).withFallback(this)

object BridgeConfigHelper {
    const val BRIDGE_PROPERTY_PREFIX = "bridge."

    private val log = LoggerFactory.getLogger(javaClass)
    fun loadConfig(baseDirectory: Path,
                   configFile: Path = baseDirectory / "firewall.conf",
                   allowMissingConfig: Boolean = false,
                   configOverrides: Config = ConfigFactory.empty()): Config {
        val parseOptions = ConfigParseOptions.defaults()
        val defaultConfig = ConfigFactory.parseResources("firewalldefault.conf", parseOptions.setAllowMissing(false))
        val appConfig = ConfigFactory.parseFile(configFile.toFile(), parseOptions.setAllowMissing(allowMissingConfig))
        val systemOverrides = systemProperties().bridgeEntriesOnly()
        val environmentOverrides = systemEnvironment().bridgeEntriesOnly()

        val finalConfig = configOverrides
                // Add substitution values here
                .withFallback(systemOverrides) //for database integration tests
                .withFallback(environmentOverrides) //for database integration tests
                .withFallback(configOf("baseDirectory" to baseDirectory.toString()))
                .withFallback(appConfig)
                .withFallback(defaultConfig)
                .resolve()
        log.info("Config:\n${finalConfig.root().render(ConfigRenderOptions.defaults())}")
        return finalConfig
    }

    private fun Config.bridgeEntriesOnly(): Config {
        return ConfigFactory.parseMap(toProperties().filterKeys { (it as String).startsWith(BRIDGE_PROPERTY_PREFIX) }.mapKeys { (it.key as String).removePrefix(BRIDGE_PROPERTY_PREFIX) })
    }

}
