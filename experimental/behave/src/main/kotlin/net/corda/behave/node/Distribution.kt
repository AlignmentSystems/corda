package net.corda.behave.node

import net.corda.behave.file.stagingRoot
import net.corda.core.CordaRuntimeException
import net.corda.core.internal.copyTo
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.utilities.contextLogger
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardCopyOption


/**
 * Corda distribution.
 */
class Distribution private constructor(

        /**
         * The distribution type of Corda: Open Source or Corda Enterprise
         */
        val type: Type,

        /**
         * The version string of the Corda distribution.
         */
        val version: String,

        /**
         * The path of the distribution fat JAR on disk, if available.
         */
        file: Path? = null,

        /**
         * Map of all distribution JAR artifacts from artifactory, if available.
         */
        val artifactUrlMap: Map<String, URL>? = null,

        /**
         *  The Docker image details, if available
         */
        val baseImage: String? = null
) {

    /**
     * The path to the distribution fat JAR.
     */
    val path: Path = file ?: nodePrefix / version

    /**
     * The path to the distribution fat JAR.
     */
    val cordaJar: Path = path / "corda.jar"

    /**
     * The path to available Cordapps for this distribution.
     */
    val cordappDirectory: Path = path / "apps"

    /**
     * The path to network bootstrapping tool.
     */
    val networkBootstrapper: Path = path / "network-bootstrapper.jar"

    /**
     * The path to the doorman jar (Corda Enterprise only).
     */
    val doormanJar: Path = path / "doorman.jar"

    /**
     * The path to the DB migration jar (Corda Enterprise only).
     */
    val dbMigrationJar: Path = nodePrefix / version / "database-manager.jar"

    /**
     * Ensure that the distribution is available on disk.
     */
    fun ensureAvailable() {
        if (cordaJar.exists()) return
        try {
            path.createDirectories()
            cordappDirectory.createDirectories()
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(System.getenv("CORDA_ARTIFACTORY_USERNAME"), System.getenv("CORDA_ARTIFACTORY_PASSWORD").toCharArray())
                }
            })
            artifactUrlMap!!.forEach { artifactName, artifactUrl ->
                artifactUrl.openStream().use {
                    if (artifactName.startsWith("corda-finance")) {
                        it.copyTo(cordappDirectory / "$artifactName.jar", StandardCopyOption.REPLACE_EXISTING)
                    } else it.copyTo(path / "$artifactName.jar", StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP response code: 401") == true) {
                log.warn("CORDA_ARTIFACTORY_USERNAME ${System.getenv("CORDA_ARTIFACTORY_USERNAME")}")
                log.warn("CORDA_ARTIFACTORY_PASSWORD ${System.getenv("CORDA_ARTIFACTORY_PASSWORD")}")
                throw Exception("Incorrect Artifactory permission. Please set CORDA_ARTIFACTORY_USERNAME and CORDA_ARTIFACTORY_PASSWORD environment variables correctly.")
            }
            throw e
        }
    }

    /**
     * Human-readable representation of the distribution.
     */
    override fun toString() = "Corda(version = $version, path = $cordaJar)"

    enum class Type(val groupId: String, val repositoryId: String, val artifacts: Set<String>, val requireAuthentication: Boolean = false) {
        CORDA_OS("net.corda", "corda-releases", setOf("corda", "corda-webserver", "corda-finance")),
        CORDA_OS_SNAPSHOT("net.corda", "corda-dev", setOf("corda", "corda-webserver", "corda-finance")),
        // bridge-server not available in Enterprise Dev Previews
        // migration-tool not published in Enterprise Dev Previews
        CORDA_ENTERPRISE("com.r3.corda", "r3-corda-releases", setOf("corda", "corda-webserver", "corda-finance", "corda-tools-database-manager"), requireAuthentication = true),
        CORDA_ENTERPRISE_SNAPSHOT("com.r3.corda", "r3-corda-dev", setOf("corda", "corda-webserver", "corda-finance", "tools-database-manager"), requireAuthentication = true)
    }

    companion object {
        private const val ARTIFACTORY_URL = "https://ci-artifactory.corda.r3cev.com/artifactory"

        private val log = contextLogger()

        private val distributions = mutableListOf<Distribution>()

        private val nodePrefix = stagingRoot / "corda"

        val MASTER = fromJarFile(Type.CORDA_OS, "corda-master")
        val R3_MASTER = fromJarFile(Type.CORDA_ENTERPRISE, "r3corda-master")

        /**
         * Get all jar artifacts from Artifactory for a given distribution and version of Corda.
         * @param type The Corda distribution type.
         * @param version The version of the Corda distribution.
         */
        fun fromArtifactory(type: Type, version: String): Distribution {
            val artifactUrlMap = resolveArtifacts(type, version)
            val distribution = Distribution(type, version, artifactUrlMap = artifactUrlMap)
            distributions.add(distribution)
            return distribution
        }

        private fun resolveArtifacts(type: Type, version: String): Map<String, URL> {
            if (type.requireAuthentication) Authenticator.setDefault(CordaArtifactoryAuthenticator)
            val urlMap = mutableMapOf<String, URL>()
            type.artifacts.forEach { artifact ->
                val artifactoryBaseUrl = "$ARTIFACTORY_URL/${type.repositoryId}/${type.groupId.replace(".", "/")}"
                val url = URL("$artifactoryBaseUrl/$artifact/$version/$artifact-$version.jar")
                log.info("Artifactory resource URL: $url")
                try {
                    url.openStream()
                    urlMap[artifact] = url
                } catch (ex: IOException) {
                    log.warn("Unable to open artifactory resource URL: ${ex.message}")
                }
            }
            return urlMap
        }

        /**
         * Get representation of a Corda distribution based on its version string and fat JAR path.
         * @param type The Corda distribution type.
         * @param version The version of the Corda distribution.
         * @param jarFile The path to the Corda fat JAR.
         */
        fun fromJarFile(type: Type, version: String, jarFile: Path? = null): Distribution {
            val distribution = Distribution(type, version, file = jarFile)
            distributions.add(distribution)
            return distribution
        }

        /**
         * Get Corda distribution from a Docker image file.
         * @param type The Corda distribution type.
         * @param baseImage The name (eg. corda) of the Corda distribution.
         * @param imageTag The version (github commit id or corda version) of the Corda distribution.
         */
        fun fromDockerImage(type: Type, baseImage: String, imageTag: String): Distribution {
            val distribution = Distribution(type, version = imageTag, baseImage = baseImage)
            distributions.add(distribution)
            return distribution
        }

        /**
         * Get registered representation of a Corda distribution based on its version string.
         * @param version The version of the Corda distribution
         */
        private val entVersionScheme = "^ENT-\\d\\.\\d.*".toRegex()     // ENT version scheme x.y
        private val osVersionScheme = "^OS-\\d\\.\\d.*".toRegex()       // OS version scheme x.y

        private val entVersionSnapshotScheme = "^ENT-\\d\\.\\d-SNAPSHOT\$".toRegex()
        private val osVersionSnapshotScheme = "^OS-\\d\\.\\d-SNAPSHOT\$".toRegex()

        fun fromVersionString(version: String): Distribution = when (version) {
            "master" -> MASTER
            "r3-master" -> R3_MASTER
            "corda-3.0" -> fromArtifactory(Type.CORDA_OS, version)
            "3.1-corda" -> fromArtifactory(Type.CORDA_OS, version)
            "R3.CORDA-3.0.0-DEV-PREVIEW-3" -> fromArtifactory(Type.CORDA_ENTERPRISE, version)
            in entVersionSnapshotScheme -> fromArtifactory(Type.CORDA_ENTERPRISE_SNAPSHOT, version.substringAfter("ENT-"))
            in osVersionSnapshotScheme -> fromArtifactory(Type.CORDA_OS_SNAPSHOT, version.substringAfter("OS-"))
            in entVersionScheme -> fromArtifactory(Type.CORDA_ENTERPRISE, version.substringAfter("ENT-"))
            in osVersionScheme -> fromArtifactory(Type.CORDA_OS, version.substringAfter("OS-"))
            else -> distributions.firstOrNull { it.version == version } ?: throw CordaRuntimeException("Invalid Corda distribution for specified version: $version")
        }
    }

    private object CordaArtifactoryAuthenticator : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication {
            return PasswordAuthentication(System.getenv("CORDA_ARTIFACTORY_USERNAME"), System.getenv("CORDA_ARTIFACTORY_PASSWORD").toCharArray())
        }
    }
}

private operator fun Regex.contains(text: CharSequence): Boolean = this.containsMatchIn(text)
