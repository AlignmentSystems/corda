/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package com.r3.corda.enterprise.perftestcordapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.enterprise.perftestcordapp.contracts.asset.Cash
import com.r3.corda.enterprise.perftestcordapp.contracts.asset.OnLedgerAsset
import com.r3.corda.enterprise.perftestcordapp.contracts.asset.PartyAndAmount
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Issued
import net.corda.core.contracts.TransactionState
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import java.util.*

/**
 * Initiates a flow that self-issues cash and then immediately spends it without coin selection.  It also then attempts
 * to notarise exactly the same transaction again, which should succeed since it is exactly the same notarisation request.
 *
 * @param amount the amount of currency to issue.
 * @param issueRef a reference to put on the issued currency.
 * @param recipient payee Party
 * @param anonymous whether to anonymise before the transaction
 * @param notary the notary to set on the output states.
 */
@StartableByRPC
class CashIssueAndDuplicatePayment(val amount: Amount<Currency>,
                                   val issueRef: OpaqueBytes,
                                   val recipient: Party,
                                   val anonymous: Boolean,
                                   val notary: Party,
                                   progressTracker: ProgressTracker) : AbstractCashFlow<AbstractCashFlow.Result>(progressTracker) {
    constructor(request: CashIssueAndPaymentFlow.IssueAndPaymentRequest) : this(request.amount, request.issueRef, request.recipient, request.anonymous, request.notary, tracker())
    constructor(amount: Amount<Currency>, issueRef: OpaqueBytes, payTo: Party, anonymous: Boolean, notary: Party) : this(amount, issueRef, payTo, anonymous, notary, tracker())

    @Suspendable
    override fun call(): Result {
        fun deriveState(txState: TransactionState<Cash.State>, amt: Amount<Issued<Currency>>, owner: AbstractParty)
                = txState.copy(data = txState.data.copy(amount = amt, owner = owner))

        val issueResult = subFlow(CashIssueFlow(amount, issueRef, notary))
        val cashStateAndRef = issueResult.stx.tx.outRef<Cash.State>(0)

        progressTracker.currentStep = GENERATING_ID
        val txIdentities = if (anonymous) {
            subFlow(SwapIdentitiesFlow(recipient))
        } else {
            emptyMap<Party, AnonymousParty>()
        }
        val anonymousRecipient = txIdentities[recipient] ?: recipient

        val changeIdentity = serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false)

        progressTracker.currentStep = GENERATING_TX
        val builder = TransactionBuilder(notary)
        val (spendTx, keysForSigning) = OnLedgerAsset.generateSpend(builder, listOf(PartyAndAmount(anonymousRecipient, amount)), listOf(cashStateAndRef),
                changeIdentity.party.anonymise(),
                { state, quantity, owner -> deriveState(state, quantity, owner) },
                { Cash().generateMoveCommand() })

        progressTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(spendTx, keysForSigning)

        progressTracker.currentStep = FINALISING_TX
        val notarised1 = finaliseTx(tx, setOf(recipient), "Unable to notarise spend first time")
        val notarised2 = finaliseTx(tx, setOf(recipient), "Unable to notarise spend second time")

        return Result(notarised2, recipient)
    }
}