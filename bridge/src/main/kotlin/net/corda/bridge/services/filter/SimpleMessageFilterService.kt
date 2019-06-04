package net.corda.bridge.services.filter

import net.corda.bridge.services.api.*
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2PMessagingHeaders
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.ClientProducer
import org.apache.activemq.artemis.api.core.client.ClientSession
import rx.Subscription

class SimpleMessageFilterService(val conf: FirewallConfiguration,
                                 val auditService: FirewallAuditService,
                                 private val artemisConnectionService: BridgeArtemisConnectionService,
                                 private val bridgeSenderService: BridgeSenderService,
                                 private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : IncomingMessageFilterService, ServiceStateSupport by stateHelper {
    companion object {
        val log = contextLogger()
    }

    private val statusFollower = ServiceStateCombiner(listOf(auditService, artemisConnectionService, bridgeSenderService))
    private var statusSubscriber: Subscription? = null
    private val whiteListedAMQPHeaders: Set<String> = conf.whitelistedHeaders.toSet()
    private var inboundSession: ClientSession? = null
    private var inboundProducer: ClientProducer? = null

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            if (it) {
                inboundSession = artemisConnectionService.started!!.sessionFactory.createSession(ArtemisMessagingComponent.NODE_P2P_USER, ArtemisMessagingComponent.NODE_P2P_USER, false, true, true, false, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE)
                inboundProducer = inboundSession!!.createProducer()
            } else {
                inboundProducer?.close()
                inboundProducer = null
                inboundSession?.close()
                inboundSession = null
            }
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
    }

    override fun stop() {
        inboundProducer?.close()
        inboundProducer = null
        inboundSession?.close()
        inboundSession = null
        stateHelper.active = false
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }

    private fun validateMessage(inboundMessage: ReceivedMessage) {
        if (!active) {
            throw IllegalStateException("Unable to forward message as Service Dependencies down")
        }
        val sourceLegalName = try {
            CordaX500Name.parse(inboundMessage.sourceLegalName)
        } catch (ex: IllegalArgumentException) {
            throw SecurityException("Invalid Legal Name ${inboundMessage.sourceLegalName}")
        }
        require(inboundMessage.payload.isNotEmpty()) { "No valid payload" }
        val validInboxTopic = bridgeSenderService.validateReceiveTopic(inboundMessage.topic, sourceLegalName)
        require(validInboxTopic) { "Topic not a legitimate Inbox for a node on this Artemis Broker ${inboundMessage.topic}" }
        require(inboundMessage.applicationProperties.keys.all { it in whiteListedAMQPHeaders }) { "Disallowed header present in ${inboundMessage.applicationProperties.keys}" }
    }

    override fun sendMessageToLocalBroker(inboundMessage: ReceivedMessage) {
        try {
            validateMessage(inboundMessage)
        } catch (ex: Exception) {
            auditService.packetDropEvent(inboundMessage, "Packet Failed validation checks: " + ex.message, RoutingDirection.INBOUND)
            inboundMessage.complete(true) // consume the bad message, so that it isn't redelivered forever.
            return
        }
        try {
            val session = inboundSession
            val producer = inboundProducer
            if (session == null || producer == null) {
                throw IllegalStateException("No artemis connection to forward message over")
            }
            val artemisMessage = session.createMessage(true)
            for (key in whiteListedAMQPHeaders) {
                if (inboundMessage.applicationProperties.containsKey(key)) {
                    artemisMessage.putObjectProperty(key, inboundMessage.applicationProperties[key])
                }
            }
            artemisMessage.putStringProperty(P2PMessagingHeaders.bridgedCertificateSubject, SimpleString(inboundMessage.sourceLegalName))
            artemisMessage.writeBodyBufferBytes(inboundMessage.payload)
            producer.send(SimpleString(inboundMessage.topic), artemisMessage) { _ -> inboundMessage.complete(true) }
            auditService.packetAcceptedEvent(inboundMessage, RoutingDirection.INBOUND)
            inboundMessage.release()
        } catch (ex: Exception) {
            log.error("Error trying to forward message", ex)
            inboundMessage.complete(false) // delivery failure. NAK back to source and await re-delivery attempts
        }
    }
}