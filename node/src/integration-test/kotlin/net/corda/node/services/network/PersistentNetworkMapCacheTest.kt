package net.corda.node.services.network

import net.corda.core.node.NodeInfo
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.configureDatabase
import net.corda.node.internal.schemas.NodeInfoSchemaV1
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.core.*
import net.corda.testing.internal.IntegrationTest
import net.corda.testing.internal.IntegrationTestSchemas
import net.corda.testing.internal.toDatabaseSchemaName
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import net.corda.testing.node.internal.makeTestDatabaseProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.*

class PersistentNetworkMapCacheTest : IntegrationTest() {
    private companion object {
        val ALICE = TestIdentity(ALICE_NAME, 70)
        val BOB = TestIdentity(BOB_NAME, 80)
        val CHARLIE = TestIdentity(CHARLIE_NAME, 90)

        @ClassRule
        @JvmField
        val databaseSchemas = IntegrationTestSchemas(CHARLIE_NAME.toDatabaseSchemaName())
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private var portCounter = 1000

    //Enterprise only - objects created in the setup method, below initialized with dummy values to avoid need for nullable type declaration
    private var database = CordaPersistence(DatabaseConfig(), emptySet())
    private var charlieNetMapCache = PersistentNetworkMapCache(database, InMemoryIdentityService(trustRoot = DEV_ROOT_CA.certificate), CHARLIE.name)

    @Before()
    fun setup() {
        //Enterprise only - for test in database mode ensure the remote database is setup before creating CordaPersistence
        super.setUp()
        database = configureDatabase(makeTestDataSourceProperties(CHARLIE_NAME.toDatabaseSchemaName()), makeTestDatabaseProperties(CHARLIE_NAME.toDatabaseSchemaName()), { null }, { null })
        charlieNetMapCache = PersistentNetworkMapCache(database, InMemoryIdentityService(trustRoot = DEV_ROOT_CA.certificate), CHARLIE.name)
    }

    @After
    fun cleanUp() {
        database.close()
    }

    @Test
    fun addNode() {
        val alice = createNodeInfo(listOf(ALICE))
        charlieNetMapCache.addNode(alice)
        assertThat(charlieNetMapCache.nodeReady).isDone()
        val fromDb = database.transaction {
            session.createQuery(
                    "from ${NodeInfoSchemaV1.PersistentNodeInfo::class.java.name}",
                    NodeInfoSchemaV1.PersistentNodeInfo::class.java
            ).resultList.map { it.toNodeInfo() }
        }
        assertThat(fromDb).containsOnly(alice)
    }

    @Test
    fun `adding the node's own node-info doesn't complete the nodeReady future`() {
        val charlie = createNodeInfo(listOf(CHARLIE))
        charlieNetMapCache.addNode(charlie)
        assertThat(charlieNetMapCache.nodeReady).isNotDone()
        assertThat(charlieNetMapCache.getNodeByLegalName(CHARLIE.name)).isEqualTo(charlie)
    }

    @Test
    fun `starting with just the node's own node-info in the db`() {
        val charlie = createNodeInfo(listOf(CHARLIE))
        saveNodeInfoIntoDb(charlie)
        assertThat(charlieNetMapCache.allNodes).containsOnly(charlie)
        charlieNetMapCache.start(emptyList())
        assertThat(charlieNetMapCache.nodeReady).isNotDone()
    }

    @Test
    fun `starting with another node-info in the db`() {
        val alice = createNodeInfo(listOf(ALICE))
        saveNodeInfoIntoDb(alice)
        assertThat(charlieNetMapCache.allNodes).containsOnly(alice)
        charlieNetMapCache.start(emptyList())
        assertThat(charlieNetMapCache.nodeReady).isDone()
    }

    @Test
    fun `unknown legal name`() {
        charlieNetMapCache.addNode(createNodeInfo(listOf(ALICE)))
        assertThat(charlieNetMapCache.getNodesByLegalName(DUMMY_NOTARY_NAME)).isEmpty()
        assertThat(charlieNetMapCache.getNodeByLegalName(DUMMY_NOTARY_NAME)).isNull()
        assertThat(charlieNetMapCache.getPeerByLegalName(DUMMY_NOTARY_NAME)).isNull()
        assertThat(charlieNetMapCache.getPeerCertificateByLegalName(DUMMY_NOTARY_NAME)).isNull()
    }

    @Test
    fun `nodes in distributed service`() {
        charlieNetMapCache.addNode(createNodeInfo(listOf(ALICE)))

        val distributedIdentity = TestIdentity(DUMMY_NOTARY_NAME)

        val distServiceNodeInfos = (1..2).map {
            val nodeInfo = createNodeInfo(identities = listOf(TestIdentity.fresh("Org-$it"), distributedIdentity))
            charlieNetMapCache.addNode(nodeInfo)
            nodeInfo
        }

        assertThat(charlieNetMapCache.getNodesByLegalName(DUMMY_NOTARY_NAME)).containsOnlyElementsOf(distServiceNodeInfos)
        assertThatIllegalArgumentException()
                .isThrownBy { charlieNetMapCache.getNodeByLegalName(DUMMY_NOTARY_NAME) }
                .withMessageContaining(DUMMY_NOTARY_NAME.toString())
    }

    @Test
    fun `get nodes by owning key and by name`() {
        val alice = createNodeInfo(listOf(ALICE))
        charlieNetMapCache.addNode(alice)
        assertThat(charlieNetMapCache.getNodesByLegalIdentityKey(ALICE.publicKey)).containsOnly(alice)
        assertThat(charlieNetMapCache.getNodeByLegalName(ALICE.name)).isEqualTo(alice)
    }

    @Test
    fun `get nodes by address`() {
        val alice = createNodeInfo(listOf(ALICE))
        charlieNetMapCache.addNode(alice)
        assertThat(charlieNetMapCache.getNodeByAddress(alice.addresses[0])).isEqualTo(alice)
    }

    @Test
    fun `insert two node infos with the same host and port`() {
        val alice = createNodeInfo(listOf(ALICE))
        charlieNetMapCache.addNode(alice)
        val bob = createNodeInfo(listOf(BOB), address = alice.addresses[0])
        charlieNetMapCache.addNode(bob)
        val nodeInfos = charlieNetMapCache.allNodes.filter { alice.addresses[0] in it.addresses }
        assertThat(nodeInfos).hasSize(2)
    }

    private fun createNodeInfo(identities: List<TestIdentity>,
                               address: NetworkHostAndPort = NetworkHostAndPort("localhost", portCounter++)): NodeInfo {
        return NodeInfo(
                addresses = listOf(address),
                legalIdentitiesAndCerts = identities.map { it.identity },
                platformVersion = 3,
                serial = 1
        )
    }

    private fun saveNodeInfoIntoDb(nodeInfo: NodeInfo) {
        database.transaction {
            session.save(NodeInfoSchemaV1.PersistentNodeInfo(
                    id = 0,
                    hash = nodeInfo.serialize().hash.toString(),
                    addresses = nodeInfo.addresses.map { NodeInfoSchemaV1.DBHostAndPort.fromHostAndPort(it) },
                    legalIdentitiesAndCerts = nodeInfo.legalIdentitiesAndCerts.mapIndexed { idx, elem ->
                        NodeInfoSchemaV1.DBPartyAndCertificate(elem, isMain = idx == 0)
                    },
                    platformVersion = nodeInfo.platformVersion,
                    serial = nodeInfo.serial
            ))
        }
    }
}
