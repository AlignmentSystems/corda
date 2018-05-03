/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.amqp

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.crypto.toStringShort
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.loggerFor
import net.corda.node.services.config.*
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.nodeapi.internal.ArtemisMessagingClient
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2PMessagingHeaders
import net.corda.nodeapi.internal.bridging.AMQPBridgeManager
import net.corda.nodeapi.internal.bridging.BridgeManager
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPServer
import net.corda.testing.core.*
import net.corda.testing.internal.rigorousMock
import org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID
import org.apache.activemq.artemis.api.core.RoutingType
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

class AMQPBridgeTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val log = loggerFor<AMQPBridgeTest>()

    private val ALICE = TestIdentity(ALICE_NAME)
    private val BOB = TestIdentity(BOB_NAME)

    private val artemisPort = freePort()
    private val artemisPort2 = freePort()
    private val amqpPort = freePort()
    private val artemisAddress = NetworkHostAndPort("localhost", artemisPort)
    private val artemisAddress2 = NetworkHostAndPort("localhost", artemisPort2)
    private val amqpAddress = NetworkHostAndPort("localhost", amqpPort)

    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Test
    fun `test acked and nacked messages`() {
        // Create local queue
        val sourceQueueName = "internal.peers." + BOB.publicKey.toStringShort()
        val (artemisServer, artemisClient, bridgeManager) = createArtemis(sourceQueueName)

        // Pre-populate local queue with 3 messages
        val artemis = artemisClient.started!!
        for (i in 0 until 3) {
            val artemisMessage = artemis.session.createMessage(true).apply {
                putIntProperty(P2PMessagingHeaders.senderUUID, i)
                writeBodyBufferBytes("Test$i".toByteArray())
                // Use the magic deduplication property built into Artemis as our message identity too
                putStringProperty(HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
            }
            artemis.producer.send(sourceQueueName, artemisMessage)
        }

        //Create target server
        val amqpServer = createAMQPServer()
        val dedupeSet = mutableSetOf<String>()

        val receive = amqpServer.onReceive.toBlocking().iterator
        amqpServer.start()

        val receivedSequence = mutableListOf<Int>()
        val atNodeSequence = mutableListOf<Int>()

        fun formatMessage(expected: String, actual: Int, received: List<Int>): String {
            return "Expected message with id $expected, got $actual, previous message receive sequence: " +
                    "${received.joinToString(",  ", "[", "]")}."
        }

        val received1 = receive.next()
        val messageID1 = received1.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
        assertArrayEquals("Test$messageID1".toByteArray(), received1.payload)
        assertEquals(0, messageID1)
        dedupeSet += received1.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
        received1.complete(true) // Accept first message
        receivedSequence += messageID1
        atNodeSequence += messageID1

        val received2 = receive.next()
        val messageID2 = received2.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
        assertArrayEquals("Test$messageID2".toByteArray(), received2.payload)
        assertEquals(1, messageID2, formatMessage("1", messageID2, receivedSequence))
        received2.complete(false) // Reject message and don't add to dedupe
        receivedSequence += messageID2 // reflects actual sequence

        // drop things until we get back to the replay
        while (true) {
            val received3 = receive.next()
            val messageID3 = received3.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID3".toByteArray(), received3.payload)
            receivedSequence += messageID3
            if (messageID3 != 1) { // keep rejecting any batched items following rejection
                received3.complete(false)
            } else { // beginnings of replay so accept again
                received3.complete(true)
                val messageId = received3.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
                if (messageId !in dedupeSet) {
                    dedupeSet += messageId
                    atNodeSequence += messageID3
                }
                break
            }
        }

        // start receiving again, but discarding duplicates
        while (true) {
            val received4 = receive.next()
            val messageID4 = received4.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID4".toByteArray(), received4.payload)
            receivedSequence += messageID4
            val messageId = received4.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
            if (messageId !in dedupeSet) {
                dedupeSet += messageId
                atNodeSequence += messageID4
            }
            received4.complete(true)
            if (messageID4 == 2) { // started to replay messages after rejection point
                break
            }
        }

        // Send a fresh item and check receive
        val artemisMessage = artemis.session.createMessage(true).apply {
            putIntProperty(P2PMessagingHeaders.senderUUID, 3)
            writeBodyBufferBytes("Test3".toByteArray())
            // Use the magic deduplication property built into Artemis as our message identity too
            putStringProperty(HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
        }
        artemis.producer.send(sourceQueueName, artemisMessage)


        // start receiving again, discarding duplicates
        while (true) {
            val received5 = receive.next()
            val messageID5 = received5.applicationProperties[P2PMessagingHeaders.senderUUID.toString()] as Int
            assertArrayEquals("Test$messageID5".toByteArray(), received5.payload)
            receivedSequence += messageID5
            val messageId = received5.applicationProperties[HDR_DUPLICATE_DETECTION_ID.toString()] as String
            if (messageId !in dedupeSet) {
                dedupeSet += messageId
                atNodeSequence += messageID5
            }
            received5.complete(true)
            if (messageID5 == 3) { // reached our fresh message
                break
            }
        }

        log.info("Message sequence: ${receivedSequence.joinToString(", ", "[", "]")}")
        log.info("Deduped sequence: ${atNodeSequence.joinToString(", ", "[", "]")}")
        assertEquals(listOf(0, 1, 2, 3), atNodeSequence)
        bridgeManager.stop()
        amqpServer.stop()
        artemisClient.stop()
        artemisServer.stop()
    }

    @Test
    @Ignore("Run only manually to check the throughput of the AMQP bridge")
    fun `AMQP full bridge throughput`() {
        val numMessages = 10000
        // Create local queue
        val sourceQueueName = "internal.peers." + BOB.publicKey.toStringShort()
        val (artemisServer, artemisClient, bridgeManager) = createArtemis(sourceQueueName)

        val artemis = artemisClient.started!!
        val queueName = ArtemisMessagingComponent.RemoteInboxAddress(BOB.publicKey).queueName

        val (artemisRecServer, artemisRecClient) = createArtemisReceiver(amqpAddress, "artemisBridge")
        //artemisBridgeClient.started!!.session.createQueue(SimpleString(queueName), RoutingType.ANYCAST, SimpleString(queueName), true)

        var numReceived = 0

        artemisRecClient.started!!.session.createQueue(SimpleString(queueName), RoutingType.ANYCAST, SimpleString(queueName), true)
        val artemisConsumer = artemisRecClient.started!!.session.createConsumer(queueName)

        val rubbishPayload = ByteArray(10 * 1024)
        var timeNanosCreateMessage = 0L
        var timeNanosSendMessage = 0L
        var timeMillisRead = 0L
        val simpleSourceQueueName = SimpleString(sourceQueueName)
        val totalTimeMillis = measureTimeMillis {
            repeat(numMessages) {
                var artemisMessage: ClientMessage? = null
                timeNanosCreateMessage += measureNanoTime {
                    artemisMessage = artemis.session.createMessage(true).apply {
                        putIntProperty("CountProp", it)
                        writeBodyBufferBytes(rubbishPayload)
                        // Use the magic deduplication property built into Artemis as our message identity too
                        putStringProperty(HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
                    }
                }
                timeNanosSendMessage += measureNanoTime {
                    artemis.producer.send(simpleSourceQueueName, artemisMessage, {})
                }
            }
            artemisClient.started!!.session.commit()


            timeMillisRead = measureTimeMillis {
                while (numReceived < numMessages) {
                    val current = artemisConsumer.receive()
                    val messageId = current.getIntProperty("CountProp")
                    assertEquals(numReceived, messageId)
                    ++numReceived
                    current.acknowledge()
                }
            }
        }
        println("Creating $numMessages messages took ${timeNanosCreateMessage / (1000 * 1000)} milliseconds")
        println("Sending $numMessages messages took ${timeNanosSendMessage / (1000 * 1000)} milliseconds")
        println("Receiving $numMessages messages took $timeMillisRead milliseconds")
        println("Total took $totalTimeMillis milliseconds")
        assertEquals(numMessages, numReceived)

        bridgeManager.stop()
        artemisClient.stop()
        artemisServer.stop()
        artemisRecClient.stop()
        artemisRecServer.stop()
    }


    private fun createArtemis(sourceQueueName: String?): Triple<ArtemisMessagingServer, ArtemisMessagingClient, BridgeManager> {
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "artemis").whenever(it).baseDirectory
            doReturn(ALICE_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn(true).whenever(it).crlCheckSoftFail
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn(artemisAddress).whenever(it).p2pAddress
            doReturn(null).whenever(it).jmxMonitoringHttpPort
            doReturn(emptyList<CertChainPolicyConfig>()).whenever(it).certificateChainCheckPolicies
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000))).whenever(it).enterpriseConfiguration
        }
        artemisConfig.configureWithDevSSLCertificate()
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", artemisPort), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig, artemisAddress, MAX_MESSAGE_SIZE, confirmationWindowSize = 10 * 1024)
        artemisServer.start()
        artemisClient.start()
        val bridgeManager = AMQPBridgeManager(artemisConfig, artemisAddress, MAX_MESSAGE_SIZE)
        bridgeManager.start()
        val artemis = artemisClient.started!!
        if (sourceQueueName != null) {
            // Local queue for outgoing messages
            artemis.session.createQueue(sourceQueueName, RoutingType.ANYCAST, sourceQueueName, true)
            bridgeManager.deployBridge(sourceQueueName, amqpAddress, setOf(BOB.name))
        }
        return Triple(artemisServer, artemisClient, bridgeManager)
    }

    private fun createArtemisReceiver(targetAdress: NetworkHostAndPort, workingDir: String): Pair<ArtemisMessagingServer, ArtemisMessagingClient> {
        val artemisConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / workingDir).whenever(it).baseDirectory
            doReturn(BOB_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn(targetAdress).whenever(it).p2pAddress
            doReturn("").whenever(it).jmxMonitoringHttpPort
            doReturn(emptyList<CertChainPolicyConfig>()).whenever(it).certificateChainCheckPolicies
            doReturn(EnterpriseConfiguration(MutualExclusionConfiguration(false, "", 20000, 40000))).whenever(it).enterpriseConfiguration
        }
        artemisConfig.configureWithDevSSLCertificate()
        val artemisServer = ArtemisMessagingServer(artemisConfig, NetworkHostAndPort("0.0.0.0", targetAdress.port), MAX_MESSAGE_SIZE)
        val artemisClient = ArtemisMessagingClient(artemisConfig, targetAdress, MAX_MESSAGE_SIZE, confirmationWindowSize = 10 * 1024)
        artemisServer.start()
        artemisClient.start()

        return Pair(artemisServer, artemisClient)

    }


    private fun createAMQPServer(): AMQPServer {
        val serverConfig = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(temporaryFolder.root.toPath() / "server").whenever(it).baseDirectory
            doReturn(BOB_NAME).whenever(it).myLegalName
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
        }
        serverConfig.configureWithDevSSLCertificate()

        return AMQPServer("0.0.0.0",
                amqpPort,
                ArtemisMessagingComponent.PEER_USER,
                ArtemisMessagingComponent.PEER_USER,
                serverConfig.loadSslKeyStore().internal,
                serverConfig.keyStorePassword,
                serverConfig.loadTrustStore().internal,
                crlCheckSoftFail = true,
                trace = true
        )
    }
}