package net.corda.node.services.transactions

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.internal.notary.AsyncCFTNotaryService
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.config.MySQLConfiguration
import java.security.PublicKey

/** Notary service backed by a replicated MySQL database. */
abstract class MySQLNotaryService(
        final override val services: ServiceHubInternal,
        override val notaryIdentityKey: PublicKey,
        configuration: MySQLConfiguration,
        /** Database table will be automatically created in dev mode */
        val devMode: Boolean) : AsyncCFTNotaryService() {

    override val asyncUniquenessProvider = MySQLUniquenessProvider(
            services.monitoringService.metrics,
            services.clock,
            configuration
    )

    override fun start() {
        if (devMode) asyncUniquenessProvider.createTable()
    }

    override fun stop() {
        asyncUniquenessProvider.stop()
    }
}

class MySQLNonValidatingNotaryService(services: ServiceHubInternal,
                                      notaryIdentityKey: PublicKey,
                                      configuration: MySQLConfiguration,
                                      devMode: Boolean = false) : MySQLNotaryService(services, notaryIdentityKey, configuration, devMode) {
    override fun createServiceFlow(otherPartySession: FlowSession): FlowLogic<Void?> = NonValidatingNotaryFlow(otherPartySession, this)
}

class MySQLValidatingNotaryService(services: ServiceHubInternal,
                                   notaryIdentityKey: PublicKey,
                                   configuration: MySQLConfiguration,
                                   devMode: Boolean = false) : MySQLNotaryService(services, notaryIdentityKey, configuration, devMode) {
    override fun createServiceFlow(otherPartySession: FlowSession): FlowLogic<Void?> = ValidatingNotaryFlow(otherPartySession, this)
}