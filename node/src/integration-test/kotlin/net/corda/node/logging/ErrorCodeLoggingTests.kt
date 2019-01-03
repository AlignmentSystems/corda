package net.corda.node.logging

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.internal.div
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.internal.IntegrationTest
import net.corda.testing.internal.IntegrationTestSchemas
import org.assertj.core.api.Assertions.assertThat
import org.junit.ClassRule
import org.junit.Test
import java.io.File

class ErrorCodeLoggingTests : IntegrationTest() {
    companion object {
        @ClassRule
        @JvmField
        val databaseSchemas = IntegrationTestSchemas(ALICE_NAME, BOB_NAME, DUMMY_BANK_A_NAME)
    }

    @Test
    fun `log entries with a throwable and ERROR or WARN get an error code appended`() {
        driver(DriverParameters(notarySpecs = emptyList())) {
            val node = startNode(startInSameProcess = false).getOrThrow()
            node.rpc.startFlow(::MyFlow).waitForCompletion()
            val logFile = node.logFile()

            val linesWithErrorCode = logFile.useLines { lines -> lines.filter { line -> line.contains("[errorCode=") }.filter { line -> line.contains("moreInformationAt=https://errors.corda.net/") }.toList() }

            assertThat(linesWithErrorCode).isNotEmpty
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class MyFlow : FlowLogic<String>() {
        override fun call(): String {
            throw IllegalArgumentException("Mwahahahah")
        }
    }
}

private fun FlowHandle<*>.waitForCompletion() {
    try {
        returnValue.getOrThrow()
    } catch (e: Exception) {
        // This is expected to throw an exception, using getOrThrow() just to wait until done.
    }
}

private fun NodeHandle.logFile(): File = (baseDirectory / "logs").toFile().walk().filter { it.name.startsWith("node-") && it.extension == "log" }.single()