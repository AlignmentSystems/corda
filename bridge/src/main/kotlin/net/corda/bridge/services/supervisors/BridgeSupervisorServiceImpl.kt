package net.corda.bridge.services.supervisors

import net.corda.bridge.services.api.*
import net.corda.bridge.services.artemis.BridgeArtemisConnectionServiceImpl
import net.corda.bridge.services.filter.SimpleMessageFilterService
import net.corda.bridge.services.ha.ExternalMasterElectionService
import net.corda.bridge.services.ha.SingleInstanceMasterService
import net.corda.bridge.services.receiver.InProcessBridgeReceiverService
import net.corda.bridge.services.receiver.TunnelingBridgeReceiverService
import net.corda.bridge.services.sender.DirectBridgeSenderService
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.utilities.contextLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Subscription

class BridgeSupervisorServiceImpl(conf: FirewallConfiguration,
                                  maxMessageSize: Int,
                                  auditService: FirewallAuditService,
                                  inProcessAMQPListenerService: BridgeAMQPListenerService?,
                                  private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : BridgeSupervisorService, ServiceStateSupport by stateHelper {
    companion object {
        private val log = contextLogger()
        private val consoleLogger : Logger = LoggerFactory.getLogger("BasicInfo")
    }

    private val haService: BridgeMasterService
    private val artemisService: BridgeArtemisConnectionService
    private val senderService: BridgeSenderService
    private val receiverService: BridgeReceiverService
    private val filterService: IncomingMessageFilterService
    private val statusFollower: ServiceStateCombiner
    private var statusSubscriber: Subscription? = null

    init {
        artemisService = BridgeArtemisConnectionServiceImpl(conf, maxMessageSize, auditService)
        haService = if (conf.haConfig == null) {
            SingleInstanceMasterService(conf, auditService)
        } else {
            ExternalMasterElectionService(conf, auditService, artemisService)
        }
        senderService = DirectBridgeSenderService(conf, maxMessageSize, auditService, haService, artemisService)
        filterService = SimpleMessageFilterService(conf, auditService, artemisService, senderService)
        receiverService = if (conf.firewallMode == FirewallMode.SenderReceiver) {
            InProcessBridgeReceiverService(conf, auditService, haService, inProcessAMQPListenerService!!, filterService)
        } else {
            require(inProcessAMQPListenerService == null) { "Should not have an in process instance of the AMQPListenerService" }
            TunnelingBridgeReceiverService(conf, auditService, haService, filterService)
        }
        statusFollower = ServiceStateCombiner(listOf(haService, senderService, receiverService, filterService))
        activeChange.subscribe({
            consoleLogger.info("BridgeSupervisorService: active = $it")
        }, { log.error("Error in state change", it) })
    }

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
        artemisService.start()
        senderService.start()
        receiverService.start()
        filterService.start()
        haService.start()
    }

    override fun stop() {
        stateHelper.active = false
        haService.stop()
        senderService.stop()
        receiverService.stop()
        filterService.stop()
        artemisService.stop()
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }
}