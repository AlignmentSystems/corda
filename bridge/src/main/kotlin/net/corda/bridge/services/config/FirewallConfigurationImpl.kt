package net.corda.bridge.services.config

import com.typesafe.config.Config
import net.corda.bridge.services.api.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.config.NodeSSLConfiguration
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.protonwrapper.netty.SocksProxyConfig
import java.nio.file.Path
import java.nio.file.Paths


fun Config.parseAsFirewallConfiguration(): FirewallConfiguration = parseAs<FirewallConfigurationImpl>()

data class BridgeSSLConfigurationImpl(override val keyStorePassword: String,
                                      override val trustStorePassword: String,
                                      override val certificatesDirectory: Path = Paths.get("certificates"),
                                      override val sslKeystore: Path = certificatesDirectory / "sslkeystore.jks",
                                      override val trustStoreFile: Path = certificatesDirectory / "truststore.jks",
                                      override val crlCheckSoftFail: Boolean) : BridgeSSLConfiguration {
    constructor(config: NodeSSLConfiguration) : this(config.keyStorePassword, config.trustStorePassword, config.certificatesDirectory, config.sslKeystore, config.trustStoreFile, config.crlCheckSoftFail)
}

data class BridgeOutboundConfigurationImpl(override val artemisBrokerAddress: NetworkHostAndPort,
                                           override val alternateArtemisBrokerAddresses: List<NetworkHostAndPort>,
                                           override val customSSLConfiguration: BridgeSSLConfigurationImpl?,
                                           override val socksProxyConfig: SocksProxyConfig? = null) : BridgeOutboundConfiguration

data class BridgeInboundConfigurationImpl(override val listeningAddress: NetworkHostAndPort,
                                          override val customSSLConfiguration: BridgeSSLConfigurationImpl?) : BridgeInboundConfiguration

data class BridgeInnerConfigurationImpl(override val floatAddresses: List<NetworkHostAndPort>,
                                        override val expectedCertificateSubject: CordaX500Name,
                                        override val customSSLConfiguration: BridgeSSLConfigurationImpl?,
                                        override val customFloatOuterSSLConfiguration: BridgeSSLConfigurationImpl?) : BridgeInnerConfiguration

data class FloatOuterConfigurationImpl(override val floatAddress: NetworkHostAndPort,
                                       override val expectedCertificateSubject: CordaX500Name,
                                       override val customSSLConfiguration: BridgeSSLConfigurationImpl?) : FloatOuterConfiguration

data class BridgeHAConfigImpl(override val haConnectionString: String, override val haPriority: Int = 10, override val haTopic: String = "/bridge/ha") : BridgeHAConfig

data class FirewallConfigurationImpl(
        override val baseDirectory: Path,
        override val certificatesDirectory: Path = baseDirectory / "certificates",
        override val sslKeystore: Path = certificatesDirectory / "sslkeystore.jks",
        override val trustStoreFile: Path = certificatesDirectory / "truststore.jks",
        override val crlCheckSoftFail: Boolean,
        override val keyStorePassword: String,
        override val trustStorePassword: String,
        override val firewallMode: FirewallMode,
        override val networkParametersPath: Path,
        override val outboundConfig: BridgeOutboundConfigurationImpl?,
        override val inboundConfig: BridgeInboundConfigurationImpl?,
        override val bridgeInnerConfig: BridgeInnerConfigurationImpl?,
        override val floatOuterConfig: FloatOuterConfigurationImpl?,
        override val haConfig: BridgeHAConfigImpl?,
        override val enableAMQPPacketTrace: Boolean,
        override val artemisReconnectionIntervalMin: Int = 5000,
        override val artemisReconnectionIntervalMax: Int = 60000,
        override val politeShutdownPeriod: Int = 1000,
        override val p2pConfirmationWindowSize: Int = 1048576,
        override val whitelistedHeaders: List<String> = ArtemisMessagingComponent.Companion.P2PMessagingHeaders.whitelistedHeaders.toList()
) : FirewallConfiguration {
    init {
        if (firewallMode == FirewallMode.SenderReceiver) {
            require(inboundConfig != null && outboundConfig != null) { "Missing required configuration" }
        } else if (firewallMode == FirewallMode.BridgeInner) {
            require(bridgeInnerConfig != null && outboundConfig != null) { "Missing required configuration" }
        } else if (firewallMode == FirewallMode.FloatOuter) {
            require(inboundConfig != null && floatOuterConfig != null) { "Missing required configuration" }
        }
    }
}


