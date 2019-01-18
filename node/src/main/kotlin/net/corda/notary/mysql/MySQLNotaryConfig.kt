package net.corda.notary.mysql

import java.util.*

data class MySQLNotaryConfig(
        val dataSource: Properties,
        /**
         * Number of times to attempt to reconnect to the database.
         */
        val connectionRetries: Int = Defaults.connectionRetries,
        /**
         * Time increment between re-connection attempts.
         *
         * The total back-off duration is calculated as: backOffIncrement * backOffBase ^ currentRetryCount
         */
        val backOffIncrement: Int = Defaults.backOffIncrement,
        /** Exponential back-off multiplier base. */
        val backOffBase: Double = Defaults.backOffBase,
        /** The maximum number of transactions processed in a single batch. */
        val maxBatchSize: Int = Defaults.maxBatchSize,
        /** The maximum combined number of input states processed in a single batch. */
        val maxBatchInputStates: Int = Defaults.maxBatchInputStates,
        /** A batch will be processed after a specified timeout even if it has not yet reached full capacity. */
        val batchTimeoutMs: Long = Defaults.batchTimeoutMs,
        /**
         * The maximum number of commit requests in flight. Once the capacity is reached the service will block on
         * further commit requests.
         */
        val maxQueueSize: Int = Defaults.maxQueueSize
) {
    internal object Defaults {
        val connectionRetries: Int = 2  // Default value for a 3 server cluster
        val backOffIncrement: Int = 500
        val backOffBase: Double = 1.5
        val maxBatchSize: Int = 500
        val maxBatchInputStates: Int = 10_000
        val batchTimeoutMs: Long = 200
        val maxQueueSize: Int = 100_000
    }

    init {
        require(connectionRetries >= 0) { "connectionRetries cannot be negative" }
    }
}
