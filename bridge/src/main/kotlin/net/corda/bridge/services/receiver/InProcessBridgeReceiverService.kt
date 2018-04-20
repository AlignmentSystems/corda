/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.bridge.services.receiver

import net.corda.bridge.services.api.*
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.internal.readAll
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import rx.Subscription

class InProcessBridgeReceiverService(val conf: BridgeConfiguration,
                                     val auditService: BridgeAuditService,
                                     haService: BridgeMasterService,
                                     val amqpListenerService: BridgeAMQPListenerService,
                                     val filterService: IncomingMessageFilterService,
                                     private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : BridgeReceiverService, ServiceStateSupport by stateHelper {
    companion object {
        val log = contextLogger()
    }

    private val statusFollower: ServiceStateCombiner
    private var statusSubscriber: Subscription? = null
    private var receiveSubscriber: Subscription? = null
    private val sslConfiguration: SSLConfiguration

    init {
        statusFollower = ServiceStateCombiner(listOf(auditService, haService, amqpListenerService, filterService))
        sslConfiguration = conf.inboundConfig?.customSSLConfiguration ?: conf
    }

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe {
            if (it) {
                val keyStoreBytes = sslConfiguration.sslKeystore.readAll()
                val trustStoreBytes = sslConfiguration.trustStoreFile.readAll()
                amqpListenerService.provisionKeysAndActivate(keyStoreBytes,
                        sslConfiguration.keyStorePassword.toCharArray(),
                        sslConfiguration.keyStorePassword.toCharArray(),
                        trustStoreBytes,
                        sslConfiguration.trustStorePassword.toCharArray())
            } else {
                if (amqpListenerService.running) {
                    amqpListenerService.wipeKeysAndDeactivate()
                }
            }
            stateHelper.active = it
        }
        receiveSubscriber = amqpListenerService.onReceive.subscribe {
            processMessage(it)
        }
    }

    private fun processMessage(receivedMessage: ReceivedMessage) {
        filterService.sendMessageToLocalBroker(receivedMessage)
    }

    override fun stop() {
        stateHelper.active = false
        if (amqpListenerService.running) {
            amqpListenerService.wipeKeysAndDeactivate()
        }
        receiveSubscriber?.unsubscribe()
        receiveSubscriber = null
        statusSubscriber?.unsubscribe()
        statusSubscriber = null
    }
}