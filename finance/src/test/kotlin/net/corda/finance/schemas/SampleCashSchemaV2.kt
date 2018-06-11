/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.finance.schemas

import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.CommonSchemaV1
import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.OpaqueBytes
import javax.persistence.*

/**
 * Second version of a cash contract ORM schema that extends the [CommonSchemaV1.FungibleState] abstract schema.
 */
object SampleCashSchemaV2 : MappedSchema(schemaFamily = CashSchema.javaClass, version = 2,
        mappedTypes = listOf(PersistentCashState::class.java)) {

    override val migrationResource = "sample-cash-v2.changelog-init"

    @Entity
    @Table(name = "cash_states_v2", indexes = [Index(name = "ccy_code_idx2", columnList = "ccy_code")])
    class PersistentCashState(
            /** product type */
            @Column(name = "ccy_code", length = 3, nullable = false)
            var currency: String,
            participants: Set<AbstractParty?>,
            owner: AbstractParty,
            quantity: Long,
            issuerParty: AbstractParty,
            issuerRef: OpaqueBytes
    ) : CommonSchemaV1.FungibleState(participants.toMutableSet(), owner, quantity, issuerParty, issuerRef.bytes) {

        @ElementCollection
        @Column(name = "participants", nullable = true)
        @CollectionTable(name = "cash_states_v2_participants", joinColumns = [JoinColumn(name = "output_index", referencedColumnName = "output_index"), JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id")])
        override var participants: MutableSet<AbstractParty?>? = null
    }
}
