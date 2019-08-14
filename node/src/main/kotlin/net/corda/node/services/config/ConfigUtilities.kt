package net.corda.node.services.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigFactory.systemEnvironment
import com.typesafe.config.ConfigFactory.systemProperties
import com.typesafe.config.ConfigParseOptions
import net.corda.cliutils.CordaSystemUtils
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.nodeapi.internal.*
import net.corda.nodeapi.internal.config.FileBasedCertificateStoreSupplier
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import net.corda.nodeapi.internal.config.toProperties
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_CA
import net.corda.nodeapi.internal.cryptoservice.ManagedCryptoService
import net.corda.nodeapi.internal.cryptoservice.azure.AzureKeyVaultCryptoService
import net.corda.nodeapi.internal.cryptoservice.bouncycastle.BCCryptoService
import net.corda.nodeapi.internal.cryptoservice.utimaco.UtimacoCryptoService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.PublicKey
import kotlin.math.min

fun configOf(vararg pairs: Pair<String, Any?>): Config = ConfigFactory.parseMap(mapOf(*pairs))
operator fun Config.plus(overrides: Map<String, Any?>): Config = ConfigFactory.parseMap(overrides).withFallback(this)

object ConfigHelper {

    // 30 is a temporary max to prevent issues with max server-side allowed database connections, until we switch to proper pooling.
    private const val FLOW_THREAD_POOL_SIZE_MAX = 30

    const val CORDA_PROPERTY_PREFIX = "corda."

    private val log = LoggerFactory.getLogger(javaClass)
    fun loadConfig(baseDirectory: Path,
                   configFile: Path = baseDirectory / "node.conf",
                   allowMissingConfig: Boolean = false,
                   configOverrides: Config = ConfigFactory.empty()): Config {
        val parseOptions = ConfigParseOptions.defaults()
        val defaultConfig = ConfigFactory.parseResources("reference.conf", parseOptions.setAllowMissing(false))
        val appConfig = ConfigFactory.parseFile(configFile.toFile(), parseOptions.setAllowMissing(allowMissingConfig))
        val databaseConfig = ConfigFactory.parseResources(System.getProperty("custom.databaseProvider") + ".conf", parseOptions.setAllowMissing(true))

        // Detect the underlying OS. If mac or windows non-server then we assume we're running in devMode. Unless specified otherwise.
        val smartDevMode = CordaSystemUtils.isOsMac() || (CordaSystemUtils.isOsWindows() && !CordaSystemUtils.getOsName().toLowerCase().contains("server"))
        val devModeConfig = ConfigFactory.parseMap(mapOf("devMode" to smartDevMode))

        // Detect the number of cores
        val coreCount = Runtime.getRuntime().availableProcessors()
        val flowThreadPoolSize = min(2 * coreCount, FLOW_THREAD_POOL_SIZE_MAX)
        val multiThreadingConfig = configOf("enterpriseConfiguration.tuning.flowThreadPoolSize" to flowThreadPoolSize.toString(),
                "enterpriseConfiguration.tuning.rpcThreadPoolSize" to (coreCount).toString())

        val systemOverrides = systemProperties().cordaEntriesOnly()
        val environmentOverrides = systemEnvironment().cordaEntriesOnly()
        val finalConfig = configOverrides
                // Add substitution values here
                .withFallback(configOf("custom.nodeOrganizationName" to parseToDbSchemaFriendlyName(baseDirectory.fileName?.toString() ?: "root"))) //for database integration tests
                .withFallback(systemOverrides) //for database integration tests
                .withFallback(environmentOverrides) //for database integration tests
                .withFallback(configOf("baseDirectory" to baseDirectory.toString()))
                .withFallback(databaseConfig) //for database integration tests
                .withFallback(appConfig)
                .withFallback(devModeConfig) // this needs to be after the appConfig, so it doesn't override the configured devMode
                .withFallback(multiThreadingConfig) // this needs to be after the appConfig, so it doesn't override the configured threading.
                .withFallback(defaultConfig)
                .resolve()

        val entrySet = finalConfig.entrySet().filter { entry -> entry.key.contains("\"") }
        for ((key) in entrySet) {
            log.error("Config files should not contain \" in property names. Please fix: $key")
        }

        return finalConfig
    }

    private fun Config.cordaEntriesOnly(): Config {

        return ConfigFactory.parseMap(toProperties().filterKeys { (it as String).startsWith(CORDA_PROPERTY_PREFIX) }.mapKeys { (it.key as String).removePrefix(CORDA_PROPERTY_PREFIX) })
    }
}

/**
 * Strictly for dev only automatically construct a server certificate/private key signed from
 * the CA certs in Node resources. Then provision KeyStores into certificates folder under node path.
 */
// TODO Move this to KeyStoreConfigHelpers.
fun NodeConfiguration.configureWithDevSSLCertificate(cryptoService: ManagedCryptoService? = null) = p2pSslOptions.configureDevKeyAndTrustStores(myLegalName, signingCertificateStore, certificatesDirectory, cryptoService)

// TODO Move this to KeyStoreConfigHelpers.
fun MutualSslConfiguration.configureDevKeyAndTrustStores(myLegalName: CordaX500Name, signingCertificateStore: FileBasedCertificateStoreSupplier, certificatesDirectory: Path, cryptoService: ManagedCryptoService? = null) {
    val specifiedTrustStore = trustStore.getOptional()

    val specifiedKeyStore = keyStore.getOptional()
    val specifiedSigningStore = signingCertificateStore.getOptional()

    if (specifiedTrustStore != null && specifiedKeyStore != null && specifiedSigningStore != null) return
    certificatesDirectory.createDirectories()

    if (specifiedTrustStore == null) {
        loadDevCaTrustStore().copyTo(trustStore.get(true))
    }

    if (specifiedKeyStore == null || specifiedSigningStore == null) {
        FileBasedCertificateStoreSupplier(keyStore.path, keyStore.storePassword, keyStore.entryPassword).get(true)
                .also { it.registerDevP2pCertificates(myLegalName) }
        when (cryptoService?.underlyingService) {
            is BCCryptoService, null -> {
                val signingKeyStore = FileBasedCertificateStoreSupplier(signingCertificateStore.path, signingCertificateStore.storePassword, signingCertificateStore.entryPassword).get(true)
                        .also { it.installDevNodeCaCertPath(myLegalName) }

                // Move distributed service composite key (generated by IdentityGenerator.generateToDisk) to keystore if exists.
                val distributedServiceKeystore = certificatesDirectory / "distributedService.jks"
                if (distributedServiceKeystore.exists()) {
                    val serviceKeystore = X509KeyStore.fromFile(distributedServiceKeystore, DEV_CA_KEY_STORE_PASS)

                    signingKeyStore.update {
                        serviceKeystore.aliases().forEach {
                            if (serviceKeystore.internal.isKeyEntry(it)) {
                                setPrivateKey(it, serviceKeystore.getPrivateKey(it, DEV_CA_KEY_STORE_PASS), serviceKeystore.getCertificateChain(it), signingKeyStore.entryPassword)
                            } else {
                                setCertificate(it, serviceKeystore.getCertificate(it))
                            }
                        }
                    }
                }
            }
            is AzureKeyVaultCryptoService, is UtimacoCryptoService -> {
                val publicKey = cryptoService.generateKeyPair(CORDA_CLIENT_CA, cryptoService.defaultIdentitySignatureScheme())
                createClientCA(publicKey, myLegalName, signingCertificateStore)
            }
            else -> throw IllegalArgumentException("CryptoService not supported.")
        }
    }
}

private fun createClientCA(publicKey: PublicKey, legalName: CordaX500Name, signingCertificateStore: FileBasedCertificateStoreSupplier) {
    FileBasedCertificateStoreSupplier(signingCertificateStore.path, signingCertificateStore.storePassword, signingCertificateStore.entryPassword).get(true)
            .also {
                it.setCertPathOnly(CORDA_CLIENT_CA, listOf(X509Utilities.createCertificate(
                        CertificateType.NODE_CA,
                        DEV_INTERMEDIATE_CA.certificate,
                        DEV_INTERMEDIATE_CA.keyPair,
                        legalName.x500Principal,
                        publicKey), DEV_INTERMEDIATE_CA.certificate, DEV_ROOT_CA.certificate))
            }
}

/** Parse a value to be database schema name friendly and removes the last part if it matches a port ("_" followed by at least 5 digits) */
fun parseToDbSchemaFriendlyName(value: String) =
        value.replace(" ", "").replace("-", "_").replace(Regex("_\\d{5,}$"), "")
