package com.r3.enclaves.txverify

import net.corda.core.identity.AnonymousParty
import net.corda.core.serialization.serialize
import net.corda.finance.POUNDS
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.testing.*
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnclaveletTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Ignore("Pending Gradle bug: https://github.com/gradle/gradle/issues/2657")
    @Test
    fun success() {
        ledger {
            // Issue a couple of cash states and spend them.
            val wtx1 = transaction {
                attachments(Cash.PROGRAM_ID)
                output(Cash.PROGRAM_ID, "c1", Cash.State(1000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MEGA_CORP_PUBKEY)))
                command(DUMMY_CASH_ISSUER.party.owningKey, Cash.Commands.Issue())
                verifies()
            }
            val wtx2 = transaction {
                attachments(Cash.PROGRAM_ID)
                output(Cash.PROGRAM_ID, "c2", Cash.State(2000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MEGA_CORP_PUBKEY)))
                command(DUMMY_CASH_ISSUER.party.owningKey, Cash.Commands.Issue())
                verifies()
            }
            val wtx3 = transaction {
                attachments(Cash.PROGRAM_ID)
                input("c1")
                input("c2")
                output(Cash.PROGRAM_ID, "c3", Cash.State(3000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MINI_CORP_PUBKEY)))
                command(MEGA_CORP_PUBKEY, Cash.Commands.Move())
                verifies()
            }
            val cashContract = MockContractAttachment(interpreter.services.cordappProvider.getContractAttachmentID(Cash.PROGRAM_ID)!!, Cash.PROGRAM_ID)
            val req = TransactionVerificationRequest(wtx3.serialize(), arrayOf(wtx1.serialize(), wtx2.serialize()), arrayOf(cashContract.serialize().bytes))
            val serialized = req.serialize()
            Files.write(Paths.get(System.getProperty("java.io.tmpdir"), "req"), serialized.bytes)
            verifyInEnclave(serialized.bytes)
        }
    }

    @Ignore("Pending Gradle bug: https://github.com/gradle/gradle/issues/2657")
    @Test
    fun fail() {
        ledger {
            // Issue a couple of cash states and spend them.
            val wtx1 = transaction {
                attachments(Cash.PROGRAM_ID)
                output(Cash.PROGRAM_ID, "c1", Cash.State(1000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MEGA_CORP_PUBKEY)))
                command(DUMMY_CASH_ISSUER.party.owningKey, Cash.Commands.Issue())
                verifies()
            }
            val wtx2 = transaction {
                attachments(Cash.PROGRAM_ID)
                output(Cash.PROGRAM_ID, "c2", Cash.State(2000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MEGA_CORP_PUBKEY)))
                command(DUMMY_CASH_ISSUER.party.owningKey, Cash.Commands.Issue())
                verifies()
            }
            val wtx3 = transaction {
                attachments(Cash.PROGRAM_ID)
                input("c1")
                input("c2")
                command(DUMMY_CASH_ISSUER.party.owningKey, DummyCommandData)
                output(Cash.PROGRAM_ID, "c3", Cash.State(3000.POUNDS `issued by` DUMMY_CASH_ISSUER, AnonymousParty(MINI_CORP_PUBKEY)))
                failsWith("Required ${Cash.Commands.Move::class.java.canonicalName} command")
            }
            val cashContract = MockContractAttachment(interpreter.services.cordappProvider.getContractAttachmentID(Cash.PROGRAM_ID)!!, Cash.PROGRAM_ID)
            val req = TransactionVerificationRequest(wtx3.serialize(), arrayOf(wtx1.serialize(), wtx2.serialize()), arrayOf(cashContract.serialize().bytes))
            val e = assertFailsWith<Exception> { verifyInEnclave(req.serialize().bytes) }
            assertTrue(e.message!!.contains("Required ${Cash.Commands.Move::class.java.canonicalName} command"))
        }
    }
}