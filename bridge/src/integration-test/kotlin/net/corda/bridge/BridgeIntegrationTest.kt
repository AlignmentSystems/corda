package net.corda.bridge

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.bridge.internal.FirewallInstance
import net.corda.bridge.services.api.FirewallMode
import net.corda.bridge.services.config.BridgeHAConfigImpl
import net.corda.core.internal.copyToDirectory
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.services.config.EnterpriseConfiguration
import net.corda.node.services.config.MutualExclusionConfiguration
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.ArtemisMessagingClient
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.BRIDGE_CONTROL
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.BRIDGE_NOTIFY
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2P_PREFIX
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.PEERS_PREFIX
import net.corda.nodeapi.internal.bridging.BridgeControl
import net.corda.nodeapi.internal.bridging.BridgeEntry
import net.corda.nodeapi.internal.bully.BullyLeaderClient
import net.corda.nodeapi.internal.network.NETWORK_PARAMS_FILE_NAME
import net.corda.nodeapi.internal.zookeeper.ZkClient
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.MAX_MESSAGE_SIZE
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.internal.rigorousMock
import net.corda.testing.internal.stubs.CertificateStoreStubs
import org.apache.activemq.artemis.api.core.RoutingType
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.curator.test.TestingServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit

class BridgeIntegrationTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val serializationEnvironment = SerializationEnvironmentRule(true)

    private val portAllocation = incrementalPortAllocation(10000)

    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Test
    fun `Load simple all in one bridge and stand it up`() {
        val configResource = "/net/corda/bridge/singleprocess/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(FirewallMode.SenderReceiver, config.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), config.outboundConfig!!.artemisBrokerAddress)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), config.inboundConfig!!.listeningAddress)
        assertNull(config.bridgeInnerConfig)
        assertNull(config.floatOuterConfig)
        config.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(config, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val stateFollower = bridge.activeChange.toBlocking().iterator
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            bridge.start()
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            bridge.stop()
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, serverListening("localhost", 10005))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    @Test
    fun `Load bridge (bridge Inner) and float outer and stand them up`() {
        val bridgeFolder = tempFolder.root.toPath()
        val bridgeConfigResource = "/net/corda/bridge/withfloat/bridge/firewall.conf"
        val bridgeConfig = createAndLoadConfigFromResource(bridgeFolder, bridgeConfigResource)
        bridgeConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        createNetworkParams(bridgeFolder)
        assertEquals(FirewallMode.BridgeInner, bridgeConfig.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), bridgeConfig.outboundConfig!!.artemisBrokerAddress)
        val floatFolder = tempFolder.root.toPath() / "float"
        val floatConfigResource = "/net/corda/bridge/withfloat/float/firewall.conf"
        val floatConfig = createAndLoadConfigFromResource(floatFolder, floatConfigResource)
        floatConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        assertEquals(FirewallMode.FloatOuter, floatConfig.firewallMode)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), floatConfig.inboundConfig!!.listeningAddress)
        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(bridgeConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val bridgeStateFollower = bridge.activeChange.toBlocking().iterator
            val float = FirewallInstance(floatConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val floatStateFollower = float.activeChange.toBlocking().iterator
            assertEquals(false, floatStateFollower.next())
            float.start()
            assertEquals(true, floatStateFollower.next())
            assertEquals(true, float.active) // float is running
            assertEquals(false, serverListening("localhost", 10005)) // but not activated
            assertEquals(false, bridgeStateFollower.next())
            bridge.start()
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005)) // now activated
            bridge.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            assertEquals(false, serverListening("localhost", 10005)) // now de-activated
            float.stop()
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, float.active)
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    @Test
    fun `Load bridge and float outer with different passwords and stand them up`() {
        val bridgeFolder = tempFolder.root.toPath()
        val bridgeConfigResource = "/net/corda/bridge/withfloatdiffpasswords/bridge/firewall.conf"
        val bridgeConfig = createAndLoadConfigFromResource(bridgeFolder, bridgeConfigResource)
        listOf(bridgeConfig.publicSSLConfiguration, bridgeConfig.bridgeInnerConfig?.tunnelSSLConfiguration).forEach { it?.createBridgeKeyStores(DUMMY_BANK_A_NAME) }
        createNetworkParams(bridgeFolder)
        assertEquals(FirewallMode.BridgeInner, bridgeConfig.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), bridgeConfig.outboundConfig!!.artemisBrokerAddress)
        val floatFolder = tempFolder.root.toPath() / "float"
        val floatConfigResource = "/net/corda/bridge/withfloatdiffpasswords/float/firewall.conf"
        val floatConfig = createAndLoadConfigFromResource(floatFolder, floatConfigResource)
        listOf(floatConfig.publicSSLConfiguration, floatConfig.inboundConfig?.customSSLConfiguration, floatConfig.floatOuterConfig?.tunnelSSLConfiguration).forEach { it?.createBridgeKeyStores(DUMMY_BANK_A_NAME) }
        assertEquals(FirewallMode.FloatOuter, floatConfig.firewallMode)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), floatConfig.inboundConfig!!.listeningAddress)
        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(bridgeConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val bridgeStateFollower = bridge.activeChange.toBlocking().iterator
            val float = FirewallInstance(floatConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val floatStateFollower = float.activeChange.toBlocking().iterator
            assertEquals(false, floatStateFollower.next())
            float.start()
            assertEquals(true, floatStateFollower.next())
            assertEquals(true, float.active) // float is running
            assertEquals(false, serverListening("localhost", 10005)) // but not activated
            assertEquals(false, bridgeStateFollower.next())
            bridge.start()
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005)) // now activated
            bridge.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            assertEquals(false, serverListening("localhost", 10005)) // now de-activated
            float.stop()
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, float.active)
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    @Test
    fun `Run HA all in one mode`() {
        val configResource = "/net/corda/bridge/hasingleprocess/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(BridgeHAConfigImpl("zk://localhost:11105,zk://localhost:11106", 10), config.haConfig)
        config.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        val zkServer = TestingServer(11105, false)
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(config, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val stateFollower = bridge.activeChange.toBlocking().iterator
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            bridge.start()
            assertEquals(false, bridge.active) // Starting the bridge insufficient to go active
            zkServer.start() // Now start zookeeper and we should be able to become active
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            val higherPriorityClient = ZkClient("localhost:11105", "/bridge/ha", "Test", 5)
            higherPriorityClient.start()
            higherPriorityClient.requestLeadership() // should win leadership and kick out our bridge
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            var socketState = true
            for (i in 0 until 5) { // The event signalling bridge down is pretty immediate, but the cascade of events leading to socket close can take a while
                socketState = serverListening("localhost", 10005)
                if (!socketState) break
                Thread.sleep(100)
            }
            assertEquals(false, socketState)
            higherPriorityClient.relinquishLeadership() // let our bridge back as leader
            higherPriorityClient.close()
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            bridge.stop() // Finally check shutdown
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, serverListening("localhost", 10005))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
            zkServer.stop()
        }
    }

    @Test
    fun `Run HA float and bridge mode`() {
        val bridgeFolder = tempFolder.root.toPath()
        val bridgeConfigResource = "/net/corda/bridge/hawithfloat/bridge/firewall.conf"
        val bridgeConfig = createAndLoadConfigFromResource(bridgeFolder, bridgeConfigResource)
        assertEquals(BridgeHAConfigImpl("zk://localhost:11105", 20, "/custom/bridge/ha"), bridgeConfig.haConfig)
        bridgeConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        createNetworkParams(bridgeFolder)
        val floatFolder = tempFolder.root.toPath() / "float"
        val floatConfigResource = "/net/corda/bridge/hawithfloat/float/firewall.conf"
        val floatConfig = createAndLoadConfigFromResource(floatFolder, floatConfigResource)
        assertNull(floatConfig.haConfig)
        floatConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        val zkServer = TestingServer(11105, false)
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(bridgeConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val bridgeStateFollower = bridge.activeChange.toBlocking().iterator
            val float = FirewallInstance(floatConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val floatStateFollower = float.activeChange.toBlocking().iterator
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, float.active)
            float.start()
            assertEquals(true, floatStateFollower.next()) // float goes active, but not listening
            assertEquals(true, float.active)
            assertEquals(false, serverListening("localhost", 10005))
            bridge.start()
            assertEquals(false, bridge.active) // Starting the bridge/float insufficient to go active
            assertEquals(true, float.active) // still active, but not listening
            assertEquals(false, serverListening("localhost", 10005))
            zkServer.start() // Now start zookeeper and we should be able to become active on float listener
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005))
            val higherPriorityClient = ZkClient("localhost:11105", "/custom/bridge/ha", "Test", 5)
            higherPriorityClient.start()
            higherPriorityClient.requestLeadership() // should win leadership and kick out our bridge
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            var socketState = true
            for (i in 0 until 5) { // The event signalling bridge down is pretty immediate, but the cascade of events leading to socket close can take a while
                socketState = serverListening("localhost", 10005)
                if (!socketState) break
                Thread.sleep(100)
            }
            assertEquals(false, socketState)
            higherPriorityClient.relinquishLeadership() // let our bridge back as leader
            higherPriorityClient.close()
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005))
            bridge.stop() // Finally check shutdown
            float.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, float.active)
            assertEquals(false, serverListening("localhost", 10005))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
            zkServer.stop()
        }
    }

    @Test
    fun `Run HA using Bully algorithm`() {
        val configResource = "/net/corda/bridge/habullysingleprocess/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(BridgeHAConfigImpl("bully://localhost", 10), config.haConfig)
        config.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(config, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val stateFollower = bridge.activeChange.toBlocking().iterator
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            bridge.start()
            assertEquals(false, bridge.active) // Starting the bridge insufficient to go active
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            val higherPriorityClient = BullyLeaderClient(artemisClient, "/bridge/ha", "Test", 5)
            higherPriorityClient.start()
            higherPriorityClient.requestLeadership() // should win leadership and kick out our bridge
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            var socketState = true
            for (i in 0 until 5) { // The event signalling bridge down is pretty immediate, but the cascade of events leading to socket close can take a while
                socketState = serverListening("localhost", 10005)
                if (!socketState) break
                Thread.sleep(100)
            }
            assertEquals(false, socketState)
            higherPriorityClient.relinquishLeadership() // let our bridge back as leader
            higherPriorityClient.close()
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            bridge.stop() // Finally check shutdown
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, serverListening("localhost", 10005))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    @Test
    fun `Test artemis failover logic`() {
        val configResource = "/net/corda/bridge/artemisfailover/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(FirewallMode.SenderReceiver, config.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), config.outboundConfig!!.artemisBrokerAddress)
        assertEquals(listOf(NetworkHostAndPort("localhost", 12005)), config.outboundConfig!!.alternateArtemisBrokerAddresses)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), config.inboundConfig!!.listeningAddress)
        assertNull(config.bridgeInnerConfig)
        assertNull(config.floatOuterConfig)
        config.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        val (artemisServer2, artemisClient2) = createArtemis2()
        val (artemisServer3, artemisClient3) = createDummyPeerArtemis()
        try {
            artemisServer2.start()
            artemisClient2.start()
            installBridgeControlResponder(artemisClient)
            installBridgeControlResponder(artemisClient2)
            val bridge = FirewallInstance(config, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val stateFollower = bridge.activeChange.toBlocking().iterator
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            bridge.start()
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", 10005))
            var bridgeTimeout = 0
            while (artemisServer3.serverControl.connectionCount < 2 && bridgeTimeout < 10) { // wait for outgoing bridge to start
                ++bridgeTimeout
                Thread.sleep(1000L)
            }
            assertTrue(bridgeTimeout < 10)
            artemisClient.stop() // Stop artemis to force failover to second choice
            artemisServer.stop()
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            bridge.stop()
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, serverListening("localhost", 10005))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
            artemisClient2.stop()
            artemisServer2.stop()
            artemisClient3.stop()
            artemisServer3.stop()
        }
    }

    @Test
    fun `Test artemis failover logic with float`() {
        val bridgeFolder = tempFolder.root.toPath()
        val bridgeConfigResource = "/net/corda/bridge/artemisfailoverandfloat/bridge/firewall.conf"
        val bridgeConfig = createAndLoadConfigFromResource(bridgeFolder, bridgeConfigResource)
        bridgeConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        createNetworkParams(bridgeFolder)
        assertEquals(FirewallMode.BridgeInner, bridgeConfig.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), bridgeConfig.outboundConfig!!.artemisBrokerAddress)
        assertEquals(listOf(NetworkHostAndPort("localhost", 12005)), bridgeConfig.outboundConfig!!.alternateArtemisBrokerAddresses)
        val floatFolder = tempFolder.root.toPath() / "float"
        val floatConfigResource = "/net/corda/bridge/artemisfailoverandfloat/float/firewall.conf"
        val floatConfig = createAndLoadConfigFromResource(floatFolder, floatConfigResource)
        floatConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        assertEquals(FirewallMode.FloatOuter, floatConfig.firewallMode)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), floatConfig.inboundConfig!!.listeningAddress)
        val (artemisServer, artemisClient) = createArtemis()
        val (artemisServer2, artemisClient2) = createArtemis2()
        val (artemisServer3, artemisClient3) = createDummyPeerArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(bridgeConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val bridgeStateFollower = bridge.activeChange.toBlocking().iterator
            val float = FirewallInstance(floatConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val floatStateFollower = float.activeChange.toBlocking().iterator
            assertEquals(false, floatStateFollower.next())
            float.start()
            assertEquals(true, floatStateFollower.next())
            assertEquals(true, float.active) // float is running
            assertEquals(false, serverListening("localhost", 10005)) // but not activated
            assertEquals(false, bridgeStateFollower.next())
            bridge.start()
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005)) // now activated
            var bridgeTimeout = 0
            while (artemisServer3.serverControl.connectionCount < 2 && bridgeTimeout < 10) { // wait for outgoing bridge to start
                ++bridgeTimeout
                Thread.sleep(1000L)
            }
            assertTrue(bridgeTimeout < 10)
            artemisClient.stop() // Stop artemis to force failover to second choice
            artemisServer.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            var timeout = 0
            var listeningState = serverListening("localhost", 10005)
            while (listeningState && timeout < 10) { // Allow a short while for close down to propagate
                ++timeout
                Thread.sleep(1000L)
                listeningState = serverListening("localhost", 10005)
            }
            assertEquals(false, listeningState) // now not activated
            artemisServer2.start() // Now enable second artemis to failover to
            artemisClient2.start()
            installBridgeControlResponder(artemisClient2)
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005)) // now activated
            bridge.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            assertEquals(false, serverListening("localhost", 10005)) // now de-activated
            float.stop()
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, float.active)
        } finally {
            artemisClient.stop()
            artemisServer.stop()
            artemisClient2.stop()
            artemisServer2.stop()
            artemisClient3.stop()
            artemisServer3.stop()
        }
    }

    @Test(timeout = 60000)
    fun `Test tunneling control message won't fail on small max message size`() {
        val bridgeFolder = tempFolder.root.toPath()
        val bridgeConfigResource = "/net/corda/bridge/withfloat/bridge/firewall.conf"
        val bridgeConfig = createAndLoadConfigFromResource(bridgeFolder, bridgeConfigResource)
        bridgeConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        createNetworkParams(bridgeFolder, maxMessageSize = 1024)
        val floatFolder = tempFolder.root.toPath() / "float"
        val floatConfigResource = "/net/corda/bridge/withfloat/float/firewall.conf"
        val floatConfig = createAndLoadConfigFromResource(floatFolder, floatConfigResource)
        floatConfig.createBridgeKeyStores(DUMMY_BANK_A_NAME)
        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(bridgeConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val bridgeStateFollower = bridge.activeChange.toBlocking().iterator
            val float = FirewallInstance(floatConfig, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val floatStateFollower = float.activeChange.toBlocking().iterator
            assertEquals(false, floatStateFollower.next())
            float.start()
            assertEquals(true, floatStateFollower.next())
            assertEquals(true, float.active) // float is running
            assertEquals(false, serverListening("localhost", 10005)) // but not activated
            assertEquals(false, bridgeStateFollower.next())
            bridge.start()
            assertEquals(true, bridgeStateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, float.active)
            assertEquals(true, serverListening("localhost", 10005)) // now activated
            bridge.stop()
            assertEquals(false, bridgeStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(true, float.active)
            assertEquals(false, serverListening("localhost", 10005)) // now de-activated
            float.stop()
            assertEquals(false, floatStateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, float.active)
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    @Test
    fun `Health check for inbound port in echo mode`() {
        val configResource = "/net/corda/bridge/singleprocess/firewall.conf"
        createNetworkParams(tempFolder.root.toPath())
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        config.createBridgeKeyStores(DUMMY_BANK_A_NAME)

        // Override some properties from the resource and use dynamically allocated ports
        val healthCheckPhrase = "ISpeakAMQP!"
        val sslHandshakeTimeout = 1000L
        var inboundPort = portAllocation.nextPort()
        // Use partial mocking to substitute overridden properties; note: they won't be reflected in log when loading config
        val inboundConfig = Mockito.spy(config.inboundConfig)
        whenever(inboundConfig?.listeningAddress).thenReturn(NetworkHostAndPort("0.0.0.0", inboundPort))
        val configMock = Mockito.spy(config)
        whenever(configMock.sslHandshakeTimeout).thenReturn(sslHandshakeTimeout)
        whenever(configMock.healthCheckPhrase).thenReturn(healthCheckPhrase)
        whenever(configMock.inboundConfig).thenReturn(inboundConfig)
        whenever(configMock.networkParametersPath).thenReturn(tempFolder.root.toPath() / NETWORK_PARAMS_FILE_NAME)

        val (artemisServer, artemisClient) = createArtemis()
        try {
            installBridgeControlResponder(artemisClient)
            val bridge = FirewallInstance(configMock, FirewallVersionInfo(1, "1.1", "Dummy", "Test"))
            val stateFollower = bridge.activeChange.toBlocking().iterator
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            bridge.start()
            assertEquals(true, stateFollower.next())
            assertEquals(true, bridge.active)
            assertEquals(true, serverListening("localhost", inboundPort))

            val address = InetSocketAddress("localhost", inboundPort)
            // NORMAL_MODE: socket should be closed by SSL handshake timeout
            AsynchronousSocketChannel.open().use {
                it.connect(address).get()
                it.write(ByteBuffer.wrap("XXXX".toByteArray()))
                assertEquals(-1, it.read(ByteBuffer.allocate(32)).get(2*sslHandshakeTimeout, TimeUnit.MILLISECONDS))
            }
            // ECHO_MODE: SSH handshake timeout is not applicable here
            AsynchronousSocketChannel.open().use {
                it.connect(address).get()
                it.write(ByteBuffer.wrap(healthCheckPhrase.toByteArray()))
                val buf = ByteBuffer.allocate(32)
                assertEquals(healthCheckPhrase.length, it.read(buf).get(sslHandshakeTimeout, TimeUnit.MILLISECONDS))
                assertEquals(healthCheckPhrase, String(buf.array(), 0, healthCheckPhrase.length))
                val nextBytes = it.read(buf);
                Thread.sleep((sslHandshakeTimeout * 3) / 2);
                assertEquals(false, nextBytes.isDone) // Make sure that socket is still alive after timeout
            }

            bridge.stop()
            assertEquals(false, stateFollower.next())
            assertEquals(false, bridge.active)
            assertEquals(false, serverListening("localhost", inboundPort))
        } finally {
            artemisClient.stop()
            artemisServer.stop()
        }
    }

    private fun createArtemis(): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val baseDirectory = tempFolder.root.toPath()
        val certificatesDirectory = baseDirectory / "certificates"
        val p2pSslConfiguration = CertificateStoreStubs.P2P.withCertificatesDirectory(certificatesDirectory)
        val signingCertificateStore = CertificateStoreStubs.Signing.withCertificatesDirectory(certificatesDirectory)
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(baseDirectory).whenever(it).baseDirectory
            doReturn(certificatesDirectory).whenever(it).certificatesDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn(signingCertificateStore).whenever(it).signingCertificateStore
            doReturn(p2pSslConfiguration).whenever(it).p2pSslOptions
            doReturn(NetworkHostAndPort("localhost", 11005)).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000), externalBridge = true)).whenever(it).enterpriseConfiguration
        }
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", 11005), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig.p2pSslOptions, NetworkHostAndPort("localhost", 11005), MAX_MESSAGE_SIZE)
        artemisServer.start()
        artemisClient.start()
        return Pair(artemisServer, artemisClient)
    }

    private fun createArtemis2(): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val baseDirectory = tempFolder.root.toPath()
        val originalCertsFolderPath = baseDirectory / "certificates"
        val p2pSslConfiguration = CertificateStoreStubs.P2P.withCertificatesDirectory(originalCertsFolderPath)
        val signingCertificateStore = CertificateStoreStubs.Signing.withCertificatesDirectory(originalCertsFolderPath)

        val folderPath = baseDirectory / "artemis2"
        val newCertsFolderPath = folderPath / "certificates"
        newCertsFolderPath.createDirectories()
        (originalCertsFolderPath / "truststore.jks").copyToDirectory(newCertsFolderPath)
        (originalCertsFolderPath / "sslkeystore.jks").copyToDirectory(newCertsFolderPath)
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(folderPath).whenever(it).baseDirectory
            doReturn(originalCertsFolderPath).whenever(it).certificatesDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn(signingCertificateStore).whenever(it).signingCertificateStore
            doReturn(p2pSslConfiguration).whenever(it).p2pSslOptions
            doReturn(NetworkHostAndPort("localhost", 12005)).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000), externalBridge = true)).whenever(it).enterpriseConfiguration
        }
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", 12005), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig.p2pSslOptions, NetworkHostAndPort("localhost", 12005), MAX_MESSAGE_SIZE)
        return Pair(artemisServer, artemisClient)
    }

    private fun createDummyPeerArtemis(): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val baseDirectory = tempFolder.root.toPath()
        val originalCertsFolderPath = baseDirectory / "certificates"
        val p2pSslConfiguration = CertificateStoreStubs.P2P.withCertificatesDirectory(originalCertsFolderPath)
        val signingCertificateStore = CertificateStoreStubs.Signing.withCertificatesDirectory(originalCertsFolderPath)

        val folderPath = baseDirectory / "artemis3"
        val newCertsFolderPath = folderPath / "certificates"
        newCertsFolderPath.createDirectories()
        (originalCertsFolderPath / "truststore.jks").copyToDirectory(newCertsFolderPath)
        (originalCertsFolderPath / "sslkeystore.jks").copyToDirectory(newCertsFolderPath)
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(folderPath).whenever(it).baseDirectory
            doReturn(newCertsFolderPath).whenever(it).certificatesDirectory
            doReturn(DUMMY_BANK_A_NAME).whenever(it).myLegalName
            doReturn(signingCertificateStore).whenever(it).signingCertificateStore
            doReturn(p2pSslConfiguration).whenever(it).p2pSslOptions
            doReturn(NetworkHostAndPort("localhost", 7890)).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000), externalBridge = true)).whenever(it).enterpriseConfiguration
        }
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", 7890), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig.p2pSslOptions, NetworkHostAndPort("localhost", 7890), MAX_MESSAGE_SIZE)
        artemisServer.start()
        artemisClient.start()
        artemisClient.started!!.session.createQueue(SimpleString("${P2P_PREFIX}12345"), RoutingType.ANYCAST, SimpleString("${P2P_PREFIX}12345"), true)
        return Pair(artemisServer, artemisClient)
    }

    private fun installBridgeControlResponder(artemisClient: ArtemisMessagingClient) {
        val artemis = artemisClient.started!!
        val inboxAddress = SimpleString("${P2P_PREFIX}Test")
        val dummyOutQueue = SimpleString("${PEERS_PREFIX}12345")
        artemis.session.createQueue(inboxAddress, RoutingType.ANYCAST, inboxAddress, true)
        artemis.session.createQueue(dummyOutQueue, RoutingType.ANYCAST, dummyOutQueue, true)
        artemis.session.createQueue(BRIDGE_NOTIFY, RoutingType.ANYCAST, BRIDGE_NOTIFY, false)
        val controlConsumer = artemis.session.createConsumer(BRIDGE_NOTIFY)
        controlConsumer.setMessageHandler { msg ->
            val outEntry = listOf(BridgeEntry(dummyOutQueue.toString(), listOf(NetworkHostAndPort("localhost", 7890)), listOf(DUMMY_BANK_A_NAME), serviceAddress = false))
            val bridgeControl = BridgeControl.NodeToBridgeSnapshot(DUMMY_BANK_A_NAME.toString(), listOf(inboxAddress.toString()), outEntry)
            val controlPacket = bridgeControl.serialize(context = SerializationDefaults.P2P_CONTEXT).bytes
            val artemisMessage = artemis.session.createMessage(false)
            artemisMessage.writeBodyBufferBytes(controlPacket)
            artemis.producer.send(BRIDGE_CONTROL, artemisMessage)
            msg.acknowledge()
        }
    }
}