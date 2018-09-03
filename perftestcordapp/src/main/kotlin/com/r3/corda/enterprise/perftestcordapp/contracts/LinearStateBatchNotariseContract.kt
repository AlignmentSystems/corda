package com.r3.corda.enterprise.perftestcordapp.contracts

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

/**
 * A lightweight `LinearState` based contract and state for use with notary performance testing.
 *
 * The verify method is mostly empty.  All it expects is a single command.  No additional vault schemas are defined.
 */
class LinearStateBatchNotariseContract : Contract {
    companion object {
        const val CP_PROGRAM_ID: ContractClassName = "com.r3.corda.enterprise.perftestcordapp.contracts.LinearStateBatchNotariseContract"
    }

    data class State(
            override val linearId: UniqueIdentifier,
            val creator: AbstractParty,
            val creationStamp: Instant
    ) : LinearState {

        override val participants = listOf(creator)
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
        class Evolve : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        tx.commands.requireSingleCommand<Commands>()
    }
}
