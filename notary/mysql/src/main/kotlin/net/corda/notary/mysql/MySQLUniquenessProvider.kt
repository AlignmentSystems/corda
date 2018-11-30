package net.corda.notary.mysql

import com.codahale.metrics.Gauge
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.SlidingWindowReservoir
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Stopwatch
import com.google.common.collect.Queues
import com.mysql.cj.jdbc.exceptions.CommunicationsException
import com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TimeWindow
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.flows.NotarisationRequestSignature
import net.corda.core.flows.NotaryError
import net.corda.core.flows.StateConsumptionDetails
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.OpenFuture
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.internal.elapsedTime
import net.corda.core.internal.notary.*
import net.corda.core.internal.notary.UniquenessProvider.Result
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.serialize
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.trace
import net.corda.serialization.internal.CordaSerializationEncoding.SNAPPY
import java.sql.*
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Uniqueness provider backed by a MySQL database. It is intended to be used with a multi-master synchronously replicated
 * variant of MySQL, such as Percona XtraDB Cluster, or MariaDB Galera Cluster.
 *
 * Note that no ORM is used since we want to retain full control over table schema and be able to experiment with optimisations.
 */
class MySQLUniquenessProvider(
        metrics: MetricRegistry,
        val clock: Clock,
        val config: MySQLNotaryConfiguration
) : UniquenessProvider, SingletonSerializeAsToken() {
    companion object {
        private val log = loggerFor<MySQLUniquenessProvider>()
        // TODO: optimize table schema for InnoDB
        const val createCommittedStateTable =
                "CREATE TABLE IF NOT EXISTS notary_committed_states (" +
                        "issue_transaction_id BINARY(32) NOT NULL," +
                        "issue_transaction_output_id INT UNSIGNED NOT NULL," +
                        "consuming_transaction_id BINARY(32) NOT NULL," +
                        "CONSTRAINT id PRIMARY KEY (issue_transaction_id, issue_transaction_output_id)" +
                        ")"
        private const val insertStateStatement = "INSERT INTO notary_committed_states (issue_transaction_id, issue_transaction_output_id, consuming_transaction_id) VALUES (?, ?, ?)"
        private const val findStateStatement = "SELECT consuming_transaction_id, issue_transaction_id, issue_transaction_output_id " +
                "FROM notary_committed_states " +
                "WHERE (issue_transaction_id = ? AND issue_transaction_output_id = ?)"
        private const val findClause = "OR (issue_transaction_id = ? AND issue_transaction_output_id = ?)"

        private const val createRequestLogTable =
                "CREATE TABLE IF NOT EXISTS notary_request_log (" +
                        "consuming_transaction_id BINARY(32) NOT NULL," +
                        "requesting_party_name TEXT NOT NULL," +
                        "request_signature BLOB NOT NULL," +
                        "request_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "request_id INT UNSIGNED NOT NULL AUTO_INCREMENT," +
                        "CONSTRAINT rid PRIMARY KEY (request_id)" +
                        ")"

        private const val createCommittedTransactionsTable =
                "CREATE TABLE IF NOT EXISTS notary_committed_transactions (" +
                        "transaction_id BINARY(32) NOT NULL," +
                        "CONSTRAINT tid PRIMARY KEY (transaction_id)" +
                        ")"

        private const val insertRequestStatement = "INSERT INTO notary_request_log (consuming_transaction_id, requesting_party_name, request_signature) VALUES (?, ?, ?)"

        private const val insertCommittedTransactionStatement = "INSERT INTO notary_committed_transactions (transaction_id) VALUES (?)"

        private const val selectCommittedTransactionStatement = "SELECT COUNT(transaction_id) FROM notary_committed_transactions WHERE transaction_id = ?"

        /** The maximum number of attempts to retry a database operation. */
        private const val maxRetries = 1000
    }

    @VisibleForTesting
    data class CommitRequest(
            val states: List<StateRef>,
            val txId: SecureHash,
            val callerIdentity: Party,
            val requestSignature: NotarisationRequestSignature,
            val timeWindow: TimeWindow?,
            val references: List<StateRef>,
            val id: UUID = UUID.randomUUID())

    private val metricPrefix = MySQLUniquenessProvider::class.simpleName
    /** Transaction commit duration timer and TPS meter. */
    private val commitTimer = metrics.timer("$metricPrefix.Commit")
    /** IPS (input states per second) meter. */
    private val inputStatesMeter = metrics.meter("$metricPrefix.IPS")
    /** Transaction batch commit duration and rate meter. */
    private val batchTimer = metrics.timer("$metricPrefix.BatchCommit")
    /**
     * When writing to multiple masters with Galera, transaction rollbacks may happen due to high write contention.
     * This is a useful heath metric.
     */
    private val rollbackCounter = metrics.counter("$metricPrefix.Rollback")
    /** Incremented when we can not obtain a DB connection. */
    private val connectionExceptionCounter = metrics.counter("$metricPrefix.ConnectionException")
    /** Track double spend attempts. Note that this will also include notarisation retries. */
    private val conflictCounter = metrics.counter("$metricPrefix.Conflicts")
    /** Track the distribution of the number of input states. **/
    private val inputStateCount = metrics.histogram("$metricPrefix.NumberOfInputStates")
    /** Track the measured ETA. **/
    private val requestProcessingETA = metrics.histogram("$metricPrefix.requestProcessingETASeconds")
    /** Track the number of requests in the queue at insert. **/
    private val requestQueueSize  = metrics.histogram("$metricPrefix.requestQueue.size")
    /** Track the number of states in the queue at insert. **/
    private val requestQueueStateCount  = metrics.histogram("$metricPrefix.requestQueue.queuedStates")

    private val dataSource = HikariDataSource(HikariConfig(config.dataSource))
    private val connectionRetries = config.connectionRetries

    /**
     * Measured in states per minute, with a minimum of 1. We take an average of the last 100 commits.
     * Minutes was chosen to increase accuracy by 60x over seconds, given we have to use longs here.
     */
    private val throughputHistory = SlidingWindowReservoir(100)
    @Volatile
    private var throughput: Double = 0.0

    /** Attempts to obtain a database connection with number of retries specified in [connectionRetries]. */
    @VisibleForTesting
    val connection: Connection
        get() {
            var retries = 0
            while (true) {
                try {
                    return dataSource.connection
                } catch (e: SQLTransientConnectionException) {
                    if (retries == connectionRetries) {
                        log.warn("Couldn't obtain connection with $retries retries, giving up", e)
                        throw e
                    }
                    retries++
                    connectionExceptionCounter.inc()
                    log.warn("Error trying to obtain a database connection, retrying. Attempts: $retries")
                    val backOffDurationMs = Math.round(
                            config.backOffIncrement * Math.pow(config.backOffBase, retries.toDouble())
                    )
                    Thread.sleep(backOffDurationMs)
                }
            }
        }

    private val requestQueue = LinkedBlockingQueue<CommitRequest>(config.maxQueueSize)
    private val nrQueuedStates = AtomicInteger(0)

    private val requestFutures = ConcurrentHashMap<UUID, OpenFuture<Result>>()

    /** Track the request queue size. */
    private val queueSizeGauge = metrics.register(
            "$metricPrefix.RequestsQueueSize",
            Gauge<Int> { requestQueue.size }
    )
    /** Track the batch size. **/
    private val processedBatchSize = metrics.histogram("$metricPrefix.ProcessedBatchSize")

    /** A request processor thread. */
    private val processorThread = thread(name = "Notary request queue processor", isDaemon = true) {
        try {
            processRequests()
        } catch (e: InterruptedException) {
        }
        log.debug { "Shutting down with ${requestQueue.size} in-flight requests unprocessed." }
    }

    /**
     * Estimated time of request processing.
     * This uses performance metrics to gauge how long the wait time for a newly queued state will probably be.
     * It checks that there is actual traffic going on (i.e. a non-zero number of states are queued and there
     * is actual throughput) and then returns the expected wait time scaled up by a factor of 2 to give a probable
     * upper bound.
     *
     * @param numStates The number of states (input + reference) we're about to request be notarised.
     */
    override fun getEta(numStates: Int): Duration {
        val rate = throughput
        val nrStates = nrQueuedStates.getAndAdd(numStates)
        requestQueueStateCount.update(nrStates)
        log.debug { "rate: $rate, queueSize: $nrStates" }
        if (rate > 0.0 && nrStates > 0) {
            val eta = Duration.ofSeconds((2 * TimeUnit.MINUTES.toSeconds(1) * nrStates / rate).toLong())
            requestProcessingETA.update(eta.seconds)
            return eta
        }
        return NotaryServiceFlow.defaultEstimatedWaitTime
    }

    private fun decrementQueueSize(requests: List<CommitRequest>): Int {
        val nrStates = requests.map { it.states.size + it.references.size }.sum()
        nrQueuedStates.addAndGet(-nrStates)
        return nrStates
    }

    /**
     * Generates and adds a [CommitRequest] to the request queue. If the request queue is full, this method will block
     * until space is available.
     *
     * Returns a future that will complete once the request is processed, containing the commit [Result].
     */
    override fun commit(
            states: List<StateRef>,
            txId: SecureHash,
            callerIdentity: Party,
            requestSignature: NotarisationRequestSignature,
            timeWindow: TimeWindow?,
            references: List<StateRef>
    ): CordaFuture<Result> {
        inputStateCount.update(states.size)
        val timer = Stopwatch.createStarted()
        val request = CommitRequest(states, txId, callerIdentity, requestSignature, timeWindow, references)
        val future = openFuture<Result>()
        requestFutures[request.id] = future
        future.then {
            recordDuration(timer)
        }
        requestQueue.put(request)
        requestQueueSize.update(requestQueue.size)
        return future
    }

    private fun recordDuration(totalTime: Stopwatch) {
        totalTime.stop()
        val elapsed = totalTime.elapsed(TimeUnit.MILLISECONDS)
        commitTimer.update(elapsed, TimeUnit.MILLISECONDS)
    }

    /**
     * Processes notarisation requests in batches. It attempts to fill the batch with up to [maxBatchSize] requests,
     * with a total combined number of input states no greater than [maxBatchInputStates].
     *
     * If there are not enough requests to fill the batch, it will get processed after a timeout of [batchTimeoutMs].
     */
    private fun processRequests() {
        val buffer = LinkedList<CommitRequest>()
        while (!Thread.interrupted()) {
            val drainedSize = Queues.drain(requestQueue, buffer, config.maxBatchSize, config.batchTimeoutMs, TimeUnit.MILLISECONDS)
            if (drainedSize == 0) continue
            processBuffer(buffer)
        }
    }

    /**
     * Processes the request [buffer], potentially splitting it into more than one if the total number of
     * inputs is over [maxBatchInputStates].
     */
    private fun processBuffer(buffer: LinkedList<CommitRequest>) {
        val numStates = decrementQueueSize(buffer)
        var inputStateCount = 0
        val batch = ArrayList<CommitRequest>()
        val duration = elapsedTime {
            while (buffer.isNotEmpty()) {
                while (buffer.isNotEmpty() && inputStateCount + buffer.peek().states.size <= config.maxBatchInputStates) {
                    val request = buffer.poll()
                    batch.add(request)
                    inputStateCount += request.states.size
                }
                log.debug { "Processing a batch of size: ${batch.size}, input states: $inputStateCount" }
                processBatch(batch)
                processedBatchSize.update(batch.size)
                inputStatesMeter.mark(inputStateCount.toLong())
                batch.clear()
                inputStateCount = 0
            }
        }
        val statesPerMinute = numStates.toLong() * TimeUnit.MINUTES.toNanos(1) / duration.toNanos()
        throughputHistory.update(maxOf(statesPerMinute, 1))
        throughput = throughputHistory.snapshot.median // Median deemed more stable / representative than mean.
    }

    private fun processBatch(requests: List<CommitRequest>) {
        val batchTime = Stopwatch.createStarted()
        try {
            runWithRetry(LogRequests(requests))
            val results = runWithRetry(CommitStates(requests, clock))
            respondWithSuccess(results)
        } catch (e: Exception) {
            // Unhandled exception, we assume that signals a problem with the database that can't be fixed with
            // a retry, such as misconfiguration.
            log.error("Error notarising transactions", e)
            respondWithError(requests)
        } finally {
            batchTime.stop()
            val elapsed = batchTime.elapsed(TimeUnit.MILLISECONDS)
            batchTimer.update(elapsed, TimeUnit.MILLISECONDS)
            log.trace { "Processed a batch of ${requests.size} requests in $elapsed ms" }
        }
    }

    /**
     * Completes request futures with a successful response. This will resume service flows that will generate and
     * send signatures back to the request originators.
     */
    private fun respondWithSuccess(results: Map<UUID, Result>) {
        for ((requestId, result) in results) {
            requestFutures[requestId]?.let {
                it.set(result)
                requestFutures.remove(requestId)
                if (result is Result.Failure && result.error is NotaryError.Conflict){
                    conflictCounter.inc()
                }
            }
        }
    }

    /**
     * If a database exception occurred when processing the batch, propagate the error to each request. This will
     * resume the service flows that will forward the error message to the request originators.
     */
    private fun respondWithError(requests: List<CommitRequest>) {
        for (request in requests) {
            requestFutures[request.id]?.let {
                it.setException(NotaryInternalException(NotaryError.General(Exception("Internal service error."))))
                requestFutures.remove(request.id)
            }
        }
    }

    /** Stores the notarisation requests including the request signature. */
    private class LogRequests(val requests: List<CommitRequest>) : DBOperation<Unit> {
        override fun execute(connection: Connection) {
            // Write request signature to log
            connection.prepareStatement(insertRequestStatement).apply {
                requests.forEach { (_, txId, callerIdentity, requestSignature) ->
                    setBytes(1, txId.bytes)
                    setString(2, callerIdentity.name.toString())
                    setBytes(3, requestSignature.serialize(context = SerializationDefaults.STORAGE_CONTEXT.withEncoding(SNAPPY)).bytes)

                    addBatch()
                    clearParameters()
                }
                executeBatch()
                close()
            }
            connection.commit()
        }
    }

    /**
     *  Stores all input states that don't yet exist in the database.
     *  A [Result.Conflict] is created for each transaction with one or more inputs already present in the database.
     */
    @VisibleForTesting
    class CommitStates(val requests: List<CommitRequest>, val clock: Clock) : DBOperation<Map<UUID, Result>> {
        override fun execute(connection: Connection): Map<UUID, Result> {
            val results = mutableMapOf<UUID, Result>()

            val allStates = requests.flatMap { it.states }.toSet()
            val allReferences = requests.flatMap { it.references }.toSet()
            val allConflicts = findAlreadyCommitted(connection, allStates, allReferences).toMutableMap()
            val toCommit = mutableListOf<CommitRequest>()

            requests.forEach { request ->
                val referenceStateConflicts = allConflicts.keys.intersect(request.references.toSet()).map { it to allConflicts[it]!! }.toMap()
                val inputStateConflicts = allConflicts.keys.intersect(request.states.toSet()).map { it to allConflicts[it]!! }.toMap()
                val conflicts = referenceStateConflicts + inputStateConflicts

                results[request.id] = if (conflicts.isNotEmpty()) {
                    if (request.states.isEmpty() && referenceStateConflicts.isNotEmpty()) {
                        if (isPreviouslySigned(connection, request.txId)) {
                            Result.Success
                        } else {
                            Result.Failure(NotaryError.Conflict(request.txId, conflicts))
                        }
                    } else if (inputStateConflicts.isNotEmpty() && isConsumedByTheSameTx(request.txId.sha256(), inputStateConflicts)) {
                        Result.Success
                    } else {
                        Result.Failure(NotaryError.Conflict(request.txId, conflicts))
                    }
                } else {
                    val outsideTimeWindowError = validateTimeWindow(clock.instant(), request.timeWindow)
                    val preSigned = outsideTimeWindowError != null && request.states.isEmpty() && isPreviouslySigned(connection, request.txId)
                    if (outsideTimeWindowError == null || preSigned) {
                        if (!preSigned) {
                            toCommit.add(request)
                        }
                        // Mark states as consumed to capture conflicting transactions in the same batch
                        request.states.forEach {
                            allConflicts[it] = StateConsumptionDetails(request.txId.sha256())
                        }
                        Result.Success
                    } else {
                        Result.Failure(outsideTimeWindowError)
                    }
                }
            }

            connection.prepareStatement(insertStateStatement).apply {
                toCommit.forEach { (states, txId, _, _) ->
                    states.forEach { stateRef ->
                        // StateRef
                        setBytes(1, stateRef.txhash.bytes)
                        setInt(2, stateRef.index)
                        // Consuming transaction
                        setBytes(3, txId.bytes)
                        addBatch()
                        clearParameters()
                    }
                }
                executeBatch()
                close()
            }

            connection.prepareStatement(insertCommittedTransactionStatement).apply {
                toCommit.forEach { (_, txId, _, _) ->
                    setBytes(1, txId.bytes)
                    addBatch()
                    clearParameters()
                }
                executeBatch()
                close()
            }
            connection.commit()
            return results
        }

        private fun isPreviouslySigned(connection: Connection, txId: SecureHash): Boolean {
            val preparedStatement = connection.prepareStatement(selectCommittedTransactionStatement)
            preparedStatement.setBytes(1, txId.bytes)
            val resultSet = preparedStatement.executeQuery()
            val nrRecords = if (resultSet.next()) {
                resultSet.getInt(1)
            } else {
                0
            }
            preparedStatement.close()
            return nrRecords == 1
        }

        private fun findAlreadyCommitted(connection: Connection, states: Set<StateRef>, references: Set<StateRef>): Map<StateRef, StateConsumptionDetails> {
            if (states.isEmpty() && references.isEmpty()) {
                return emptyMap()
            }
            val queryString = buildQueryString((states + references).size)
            val preparedStatement = connection.prepareStatement(queryString).apply {
                var parameterIndex = 0
                (states + references).forEach { (txId, index) ->
                    setBytes(++parameterIndex, txId.bytes)
                    setInt(++parameterIndex, index)
                }
            }
            val resultSet = preparedStatement.executeQuery()
            val committedStates = mutableMapOf<StateRef, StateConsumptionDetails>()
            while (resultSet.next()) {
                val consumingTxId = SecureHash.SHA256(resultSet.getBytes(1))
                val stateRef = StateRef(SecureHash.SHA256(resultSet.getBytes(2)), resultSet.getInt(3))
                committedStates[stateRef] = if (stateRef in references) {
                    StateConsumptionDetails(consumingTxId.sha256(), StateConsumptionDetails.ConsumedStateType.REFERENCE_INPUT_STATE)
                } else {
                    StateConsumptionDetails(consumingTxId.sha256())
                }
            }
            preparedStatement.close()
            return committedStates
        }

        private fun buildQueryString(stateCount: Int): String {
            val queryStringBuilder = StringBuilder(findStateStatement)
            (1 until stateCount).forEach { queryStringBuilder.append(findClause) }
            return queryStringBuilder.toString()
        }
    }

    /** An interface for custom database operations. */
    private interface DBOperation<out T> {
        fun execute(connection: Connection): T
    }

    /** Runs the provided [operation], retrying on transient database errors. */
    private fun <T> runWithRetry(operation: DBOperation<T>): T {
        var retryCount = 0
        while (retryCount < maxRetries) {
            connection.use {
                sameConnection@ while (retryCount < maxRetries) {
                    retryCount++
                    try {
                        return operation.execute(it)
                    } catch (e: Exception) {
                        when (e) {
                            is BatchUpdateException, // Occurs when a competing transaction commits conflicting input states
                            is MySQLTransactionRollbackException -> {
                                log.warn("Database transaction conflict, retrying", e)
                                it.rollback()
                                rollbackCounter.inc()
                                continue@sameConnection // Retrying using the same connection
                            }
                            is SQLRecoverableException, is CommunicationsException, // Occurs when an issue is encountered during execute() (e.g. connection lost)
                            is SQLNonTransientConnectionException -> { // Occurs when an issue is encountered during commit() (e.g. connection lost)
                                log.warn("Lost connection to the database, retrying", e)
                                break@sameConnection // Retrying using a new connection
                                // TODO: don't reinsert notarisation request on retry
                            }
                            else -> {
                                log.warn("Unexpected error occurred, attempting to rollback", e)
                                it.rollback()
                                throw e
                            }
                        }
                    }
                }
            }
        }
        throw IllegalStateException("Database operation reached the maximum number of retries: $retryCount, something went wrong.")
    }

    fun createTable() {
        log.debug("Attempting to create DB table if it does not yet exist: $createCommittedStateTable")
        connection.use {
            it.createStatement().execute(createCommittedStateTable)
            it.createStatement().execute(createRequestLogTable)
            it.createStatement().execute(createCommittedTransactionsTable)
            it.commit()
        }
    }

    fun stop() {
        dataSource.close()
        processorThread.interrupt()
    }
}
