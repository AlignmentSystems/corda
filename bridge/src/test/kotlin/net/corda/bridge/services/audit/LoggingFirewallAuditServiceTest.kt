package net.corda.bridge.services.audit

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import net.corda.bridge.services.api.AuditServiceConfiguration
import net.corda.bridge.services.api.FirewallConfiguration
import net.corda.bridge.services.api.RoutingDirection
import net.corda.nodeapi.internal.protonwrapper.messages.ApplicationMessage
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import net.corda.nodeapi.internal.protonwrapper.messages.SendableMessage
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress

class LoggingFirewallAuditServiceTest {

    private val instance : LoggingFirewallAuditService

    init {
        val auditServiceConfiguration = mock<AuditServiceConfiguration>()
        val firewallConfiguration = mock<FirewallConfiguration>()
        whenever(firewallConfiguration.auditServiceConfiguration).then { auditServiceConfiguration }
        whenever(auditServiceConfiguration.loggingIntervalSec).then { 50L }
        instance = LoggingFirewallAuditService(firewallConfiguration)
    }

    @After
    fun tearDown() {
        instance.stop()
    }

    @Test
    fun testStatsOutput() {

        fun Int.toDirection() : RoutingDirection {
            return if (this % 3 == 0) {
                RoutingDirection.INBOUND
            } else {
                RoutingDirection.OUTGOING
            }
        }

        val byteArray = ByteArray(20)

        fun Int.createMessage(direction : RoutingDirection) : ApplicationMessage {
            val partyName = "Party" + ((this % 4) + 1)
            return when(direction) {
                RoutingDirection.INBOUND -> {
                    val msg = mock<ReceivedMessage>()
                    whenever(msg.payload).then { byteArray }
                    whenever(msg.sourceLegalName).then { partyName }
                    msg
                }
                RoutingDirection.OUTGOING -> {
                    val msg = mock<SendableMessage>()
                    whenever(msg.payload).then { byteArray }
                    whenever(msg.destinationLegalName).then { partyName }
                    msg
                }
            }
        }

        fun Int.toAddress(): InetSocketAddress {
            val hostname = "Server" + ((this % 5) + 1)
            return InetSocketAddress(hostname, 10001)
        }

        val failedConnCount = 7
        (1..failedConnCount).forEach { instance.failedConnectionEvent(it.toAddress(), null, "test", it.toDirection()) }
        val succConnCount = 40
        (1..succConnCount).forEach { instance.successfulConnectionEvent(it.toAddress(), "test2", "test", it.toDirection()) }

        val packetDropCount = 20
        // Multi-threaded operation to make sure the state data structure is sound in that regard
        (1..packetDropCount).toList().parallelStream().forEach { val direction = it.toDirection()
            instance.packetDropEvent(it.createMessage(direction), "test3", direction) }

        val packetAcceptedCount = 9000
        // Multi-threaded operation to make sure the state data structure is sound in that regard
        (1..packetAcceptedCount).toList().parallelStream().forEach {
            val direction = it.toDirection()
            instance.packetAcceptedEvent(it.createMessage(direction), direction)
        }

       with(instance.prepareStatsAndReset()) {
           assertThat(this, containsSubstring("Successful connection count: 13(inbound), 27(outgoing)"))
           assertThat(this, containsSubstring("Failed connection count: 2(inbound), 5(outgoing)"))
           assertThat(this, containsSubstring("Packets accepted count: 3,000(inbound), 6,000(outgoing)"))
           assertThat(this, containsSubstring("Bytes transmitted: 60,000(inbound), 120,000(outgoing)"))
           assertThat(this, containsSubstring("Packets dropped count: 6(inbound), 14(outgoing)"))
           assertThat(this, containsSubstring("Failed connections out:"))
           assertThat(this, containsSubstring("Server5:10001 -> 1"))
       }

        // Ensure reset stats
        with(instance.prepareStatsAndReset()) {
            assertThat(this, containsSubstring("Successful connection count: 0"))
            assertThat(this, containsSubstring("Packets dropped count: 0(inbound), 0(outgoing)"))
            assertThat(this, containsSubstring("Failed connections out:").not())
            assertThat(this, containsSubstring("Accepted packets out:").not())
        }
    }
}

/*
During last PT1M stats were as follows:
Load average: N/A
Memory:
	Free: 230 MB
	Total: 702 MB
	Max: 7,243 MB
Traffic totals:
	Successful connection count: 13(inbound), 27(outgoing)
	Failed connection count: 2(inbound), 5(outgoing)
	Packets accepted count: 3,000(inbound), 6,000(outgoing)
	Bytes transmitted: 60,000(inbound), 120,000(outgoing)
	Packets dropped count: 6(inbound), 14(outgoing)
Traffic breakdown:
	Successful connections in:
		Server5:10001 -> 3
		Server4:10001 -> 3
		Server3:10001 -> 2
		Server2:10001 -> 3
		Server1:10001 -> 2
	Successful connections out:
		Server5:10001 -> 5
		Server4:10001 -> 5
		Server3:10001 -> 6
		Server2:10001 -> 5
		Server1:10001 -> 6
	Failed connections in:
		Server4:10001 -> 1
		Server2:10001 -> 1
	Failed connections out:
		Server5:10001 -> 1
		Server3:10001 -> 2
		Server2:10001 -> 1
		Server1:10001 -> 1
	Accepted packets in:
		Party1 -> 750
		Party2 -> 750
		Party3 -> 750
		Party4 -> 750
	Accepted packets out:
		Party1 -> 1,500
		Party2 -> 1,500
		Party3 -> 1,500
		Party4 -> 1,500
	Dropped packets in:
		Party1 -> 1
		Party2 -> 1
		Party3 -> 2
		Party4 -> 2
	Dropped packets out:
		Party1 -> 4
		Party2 -> 4
		Party3 -> 3
		Party4 -> 3
 */