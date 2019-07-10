package net.corda.bridge.services.artemis

import net.corda.bridge.services.api.BridgeArtemisConnectionService
import net.corda.bridge.services.api.FirewallAuditService
import net.corda.bridge.services.api.FirewallConfiguration
import net.corda.bridge.services.api.ServiceStateSupport
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.internal.ThreadBox
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.ArtemisMessagingClient
import net.corda.nodeapi.internal.ArtemisMessagingClient.Companion.CORDA_ARTEMIS_CALL_TIMEOUT_DEFAULT
import net.corda.nodeapi.internal.ArtemisMessagingClient.Companion.CORDA_ARTEMIS_CALL_TIMEOUT_PROP_NAME
import net.corda.nodeapi.internal.ArtemisMessagingComponent
import net.corda.nodeapi.internal.ArtemisTcpTransport
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import org.apache.activemq.artemis.api.core.client.FailoverEventType
import org.apache.activemq.artemis.api.core.client.ServerLocator
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants
import rx.Subscription
import java.lang.Long.min
import java.util.concurrent.CountDownLatch

class BridgeArtemisConnectionServiceImpl(val conf: FirewallConfiguration,
                                         val maxMessageSize: Int,
                                         val auditService: FirewallAuditService,
                                         private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : BridgeArtemisConnectionService, ServiceStateSupport by stateHelper {
    companion object {
        val log = contextLogger()
    }

    private class InnerState {
        var running = false
        var locator: ServerLocator? = null
        var started: ArtemisMessagingClient.Started? = null
        var connectThread: Thread? = null
    }

    private val state = ThreadBox(InnerState())
    private val sslConfiguration: MutualSslConfiguration
    private val statusFollower: ServiceStateCombiner
    private var statusSubscriber: Subscription? = null

    init {
        statusFollower = ServiceStateCombiner(listOf(auditService))
        sslConfiguration = conf.outboundConfig?.artemisSSLConfiguration ?: conf.publicSSLConfiguration
    }

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            if (it) {
                startArtemisConnection()
            } else {
                stopArtemisConnection()
            }
        }, { log.error("Error in state change", it) })
    }

    private fun startArtemisConnection() {
        state.locked {
            check(!running) { "start can't be called twice" }
            running = true
            val outboundConf = conf.outboundConfig!!
            log.info("Connecting to message broker: ${outboundConf.artemisBrokerAddress}")
            val brokerAddresses = listOf(outboundConf.artemisBrokerAddress) + outboundConf.alternateArtemisBrokerAddresses
            val tcpTransports = brokerAddresses.map { ArtemisTcpTransport.p2pConnectorTcpTransport(it, sslConfiguration) }
            locator = ActiveMQClient.createServerLocatorWithoutHA(*tcpTransports.toTypedArray()).apply {
                // Never time out on our loopback Artemis connections. If we switch back to using the InVM transport this
                // would be the default and the two lines below can be deleted.
                connectionTTL = 60000
                clientFailureCheckPeriod = 30000
                callFailoverTimeout = java.lang.Long.getLong(CORDA_ARTEMIS_CALL_TIMEOUT_PROP_NAME, CORDA_ARTEMIS_CALL_TIMEOUT_DEFAULT)
                callTimeout = java.lang.Long.getLong(CORDA_ARTEMIS_CALL_TIMEOUT_PROP_NAME, CORDA_ARTEMIS_CALL_TIMEOUT_DEFAULT)
                minLargeMessageSize = maxMessageSize
                isUseGlobalPools = nodeSerializationEnv != null
                confirmationWindowSize = conf.p2pConfirmationWindowSize
                producerWindowSize = -1
            }
            connectThread = Thread({ artemisReconnectionLoop() }, "Artemis Connector Thread").apply {
                isDaemon = true
            }
            connectThread!!.start()
        }
    }

    override fun stop() {
        stopArtemisConnection()
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }

    private fun stopArtemisConnection() {
        stateHelper.active = false
        val connectThread = state.locked {
            if (running) {
                log.info("Shutdown artemis")
                running = false
                started?.apply {
                    producer.close()
                    session.close()
                    sessionFactory.close()
                }
                started = null
                locator?.close()
                locator = null
                val thread = connectThread
                connectThread = null
                thread
            } else null
        }
        connectThread?.interrupt()
        connectThread?.join(conf.politeShutdownPeriod.toLong())
    }

    override val started: ArtemisMessagingClient.Started?
        get() = state.locked { started }

    private fun artemisReconnectionLoop() {
        var tcpIndex = 0
        var reconnectInterval = conf.artemisReconnectionIntervalMin.toLong()
        while (state.locked { running }) {
            val locator = state.locked { locator }
            if (locator == null) {
                break
            }
            try {
                val transport = locator.staticTransportConfigurations[tcpIndex]
                tcpIndex = (tcpIndex + 1).rem(locator.staticTransportConfigurations.size)
                log.info("Try create session factory ${transport.params[TransportConstants.HOST_PROP_NAME]}:${transport.params[TransportConstants.PORT_PROP_NAME]}")
                val newSessionFactory = locator.createSessionFactory(transport)
                log.info("Got session factory")
                val latch = CountDownLatch(1)
                newSessionFactory.connection.addCloseListener {
                    log.info("Connection close event")
                    latch.countDown()
                }
                newSessionFactory.addFailoverListener { evt: FailoverEventType ->
                    log.info("Session failover Event $evt")
                    if (evt == FailoverEventType.FAILOVER_FAILED) {
                        latch.countDown()
                    }
                }
                val newSession = newSessionFactory.createSession(ArtemisMessagingComponent.NODE_P2P_USER,
                        ArtemisMessagingComponent.NODE_P2P_USER,
                        false,
                        true,
                        true,
                        locator.isPreAcknowledge,
                        ActiveMQClient.DEFAULT_ACK_BATCH_SIZE)
                newSession.start()
                log.info("Session created")
                val newProducer = newSession.createProducer()
                state.locked {
                    reconnectInterval = conf.artemisReconnectionIntervalMin.toLong()
                    started = ArtemisMessagingClient.Started(locator, newSessionFactory, newSession, newProducer)
                }
                stateHelper.active = true
                latch.await()
                state.locked {
                    started?.apply {
                        producer.close()
                        session.close()
                        sessionFactory.close()
                    }
                    started = null
                }
                stateHelper.active = false
                log.info("Session closed")
            } catch (ex: Exception) {
                log.warn("Exception in re-connect loop: " + ex.message)
                log.trace("Caught exception", ex)
            }

            try {
                // Sleep for a short while before attempting reconnect
                Thread.sleep(reconnectInterval)
            } catch (ex: InterruptedException) {
                // ignore
            }
            reconnectInterval = min(2L * reconnectInterval, conf.artemisReconnectionIntervalMax.toLong())
        }
        log.info("Ended Artemis Connector Thread")
    }
}