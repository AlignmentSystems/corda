/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.node.services.persistence

import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.contextLogger
import javax.persistence.AttributeConverter
import javax.persistence.Converter

/**
 * Converter to persist a party as its' well known identity (where resolvable).
 * Completely anonymous parties are stored as null (to preserve privacy).
 */
@Converter(autoApply = true)
class AbstractPartyToX500NameAsStringConverter(private val wellKnownPartyFromX500Name: (CordaX500Name) -> Party?,
                                               private val wellKnownPartyFromAnonymous: (AbstractParty) -> Party?) : AttributeConverter<AbstractParty, String> {
    companion object {
        private val log = contextLogger()
    }

    override fun convertToDatabaseColumn(party: AbstractParty?): String? {
        if (party != null) {
            val partyName = wellKnownPartyFromAnonymous(party)?.toString()
            if (partyName != null) return partyName
            log.warn("Identity service unable to resolve AbstractParty: $party")
        }
        return null // non resolvable anonymous parties
    }

    override fun convertToEntityAttribute(dbData: String?): AbstractParty? {
        if (dbData != null) {
            val party = wellKnownPartyFromX500Name(CordaX500Name.parse(dbData))
            if (party != null) return party
        }
        return null // non resolvable anonymous parties are stored as nulls
    }
}
