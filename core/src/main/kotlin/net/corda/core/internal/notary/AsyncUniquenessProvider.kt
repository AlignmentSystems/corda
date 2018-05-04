/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.core.internal.notary

import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TimeWindow
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.NotarisationRequestSignature
import net.corda.core.flows.NotaryError
import net.corda.core.identity.Party

/**
 * A service that records input states of the given transaction and provides conflict information
 * if any of the inputs have already been used in another transaction.
 */
interface AsyncUniquenessProvider : UniquenessProvider {
    /** Commits all input states of the given transaction. */
    fun commitAsync(states: List<StateRef>, txId: SecureHash, callerIdentity: Party, requestSignature: NotarisationRequestSignature, timeWindow: TimeWindow?): CordaFuture<Result>

    /** Commits all input states of the given transaction synchronously. Use [commitAsync] for better performance. */
    override fun commit(states: List<StateRef>, txId: SecureHash, callerIdentity: Party, requestSignature: NotarisationRequestSignature, timeWindow: TimeWindow?) {
        val result = commitAsync(states, txId, callerIdentity, requestSignature, timeWindow).get()
        if (result is Result.Failure) {
            throw NotaryInternalException(result.error)
        }
    }

    /** The outcome of committing a transaction. */
    sealed class Result {
        /** Indicates that all input states have been committed successfully. */
        object Success : Result()
        /** Indicates that the transaction has not been committed. */
        data class Failure(val error: NotaryError) : Result()
    }
}

