package net.corda.node.internal.cordapp

import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.ContractUpgradeFlow
import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.location
import net.corda.node.VersionInfo
import net.corda.node.services.transactions.NodeNotarySchemaV1
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.notary.experimental.bftsmart.BFTSmartNotarySchemaV1
import net.corda.notary.experimental.bftsmart.BFTSmartNotaryService
import net.corda.notary.experimental.raft.RaftNotarySchemaV1
import net.corda.notary.experimental.raft.RaftNotaryService
import net.corda.notary.mysql.MySQLNotaryService

internal object VirtualCordapp {
    /** A list of the core RPC flows present in Corda */
    private val coreRpcFlows = listOf(
            ContractUpgradeFlow.Initiate::class.java,
            ContractUpgradeFlow.Authorise::class.java,
            ContractUpgradeFlow.Deauthorise::class.java
    )

    /** A Cordapp representing the core package which is not scanned automatically. */
    fun generateCore(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = coreRpcFlows,
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(),
                info = Cordapp.Info.Default("corda-core", versionInfo.vendor, versionInfo.releaseVersion, "Copyright 2019 R3. Licensed use only"),
                allFlows = listOf(),
                jarPath = ContractUpgradeFlow.javaClass.location, // Core JAR location
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = versionInfo.platformVersion,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = null,
                isLoaded = false
        )
    }

    /** A Cordapp for the built-in notary service implementation. */
    fun generateSimpleNotary(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = listOf(),
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(NodeNotarySchemaV1),
                info = Cordapp.Info.Default("corda-notary", versionInfo.vendor, versionInfo.releaseVersion, "Copyright 2019 R3. Licensed use only"),
                allFlows = listOf(),
                jarPath = SimpleNotaryService::class.java.location,
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = versionInfo.platformVersion,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = SimpleNotaryService::class.java,
                isLoaded = false
        )
    }

    /** A Cordapp for the built-in Raft notary service implementation. */
    fun generateRaftNotary(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = listOf(),
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(RaftNotarySchemaV1),
                info = Cordapp.Info.Default("corda-notary-raft", versionInfo.vendor, versionInfo.releaseVersion, "Copyright 2019 R3. Licensed use only"),
                allFlows = listOf(),
                jarPath = RaftNotaryService::class.java.location,
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = versionInfo.platformVersion,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = RaftNotaryService::class.java,
                isLoaded = false
        )
    }

    /** A Cordapp for the built-in BFT-Smart notary service implementation. */
    fun generateBFTSmartNotary(versionInfo: VersionInfo): CordappImpl {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = listOf(),
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = setOf(BFTSmartNotarySchemaV1),
                info = Cordapp.Info.Default("corda-notary-bft-smart", versionInfo.vendor, versionInfo.releaseVersion, "Copyright 2019 R3. Licensed use only"),
                allFlows = listOf(),
                jarPath = BFTSmartNotaryService::class.java.location,
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = versionInfo.platformVersion,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = BFTSmartNotaryService::class.java,
                isLoaded = false
        )
    }

    /** A Cordapp for the built-in MySQL notary service implementation. */
    fun generateMySQLNotary(versionInfo: VersionInfo): CordappImpl? {
        return CordappImpl(
                contractClassNames = listOf(),
                initiatedFlows = listOf(),
                rpcFlows = listOf(),
                serviceFlows = listOf(),
                schedulableFlows = listOf(),
                services = listOf(),
                serializationWhitelists = listOf(),
                serializationCustomSerializers = listOf(),
                customSchemas = emptySet(),
                info = Cordapp.Info.Default("corda-notary-mysql", versionInfo.vendor, versionInfo.releaseVersion, "Copyright 2019 R3. Licensed use only"),
                allFlows = listOf(),
                jarPath = MySQLNotaryService::class.java.location,
                jarHash = SecureHash.allOnesHash,
                minimumPlatformVersion = versionInfo.platformVersion,
                targetPlatformVersion = versionInfo.platformVersion,
                notaryService = MySQLNotaryService::class.java,
                isLoaded = false
        )
    }
}