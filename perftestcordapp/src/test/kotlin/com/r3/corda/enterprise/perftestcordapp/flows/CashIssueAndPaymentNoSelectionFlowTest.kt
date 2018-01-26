package com.r3.corda.enterprise.perftestcordapp.flows

import com.r3.corda.enterprise.perftestcordapp.DOLLARS
import com.r3.corda.enterprise.perftestcordapp.`issued by`
import com.r3.corda.enterprise.perftestcordapp.contracts.asset.Cash
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.trackBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.core.*
import net.corda.testing.node.InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.node.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class CashIssueAndPayNoSelectionTests(private val anonymous: Boolean) {
    companion object {
        @Parameterized.Parameters(name = "Anonymous = {0}")
        @JvmStatic
        fun data() = listOf(false, true)
    }

    private lateinit var mockNet: MockNetwork
    private val ref = OpaqueBytes.of(0x01)
    private lateinit var bankOfCordaNode: StartedNode<MockNode>
    private lateinit var bankOfCorda: Party
    private lateinit var aliceNode: StartedNode<MockNode>
    private lateinit var notary: Party

    @Before
    fun start() {
        mockNet = MockNetwork(servicePeerAllocationStrategy = RoundRobin(),
                cordappPackages = listOf("com.r3.corda.enterprise.perftestcordapp.contracts.asset", "com.r3.corda.enterprise.perftestcordapp.schemas"))
        bankOfCordaNode = mockNet.createPartyNode(BOC_NAME)
        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bankOfCorda = bankOfCordaNode.info.chooseIdentity()
        mockNet.runNetwork()
        notary = mockNet.defaultNotaryIdentity
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `issue and pay some cash`() {
        val payTo = aliceNode.info.chooseIdentity()
        val expectedPayment = 500.DOLLARS

        bankOfCordaNode.database.transaction {
            // Register for vault updates
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val (_, vaultUpdatesBoc)
                    = bankOfCordaNode.services.vaultService.trackBy<Cash.State>(criteria)
            val (_, vaultUpdatesBankClient)
                    = aliceNode.services.vaultService.trackBy<Cash.State>(criteria)

            val future = bankOfCordaNode.services.startFlow(CashIssueAndPaymentNoSelection(
                    expectedPayment, OpaqueBytes.of(1), payTo, anonymous, notary)).resultFuture
            mockNet.runNetwork()
            future.getOrThrow()

            // Check bank of corda vault - should see two consecutive updates of issuing $500
            // and paying $500 to alice
            vaultUpdatesBoc.expectEvents {
                sequence(
                        expect { update ->
                            require(update.produced.size == 1) { "Expected 1 produced states, actual: $update" }
                            val changeState = update.produced.single().state.data
                            assertEquals(expectedPayment.`issued by`(bankOfCorda.ref(ref)), changeState.amount)
                        },
                        expect { update ->
                            require(update.consumed.size == 1) { "Expected 1 consumed states, actual: $update" }
                        }
                )
            }

            // Check notary node vault updates
            vaultUpdatesBankClient.expectEvents {
                expect { (consumed, produced) ->
                    require(consumed.isEmpty()) { consumed.size }
                    require(produced.size == 1) { produced.size }
                    val paymentState = produced.single().state.data
                    assertEquals(expectedPayment.`issued by`(bankOfCorda.ref(ref)), paymentState.amount)
                }
            }
        }
    }
}
