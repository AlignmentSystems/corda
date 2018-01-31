package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Fiber.parkAndSerialize
import co.paralleluniverse.fibers.FiberScheduler
import co.paralleluniverse.fibers.Suspendable
import co.paralleluniverse.strands.Strand
import co.paralleluniverse.strands.channels.Channel
import com.codahale.metrics.Counter
import net.corda.core.concurrent.CordaFuture
import net.corda.core.context.InvocationContext
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.FlowIORequest
import net.corda.core.internal.FlowStateMachine
import net.corda.core.internal.isRegularFile
import net.corda.core.internal.uncheckedCast
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.serialize
import net.corda.core.utilities.Try
import net.corda.core.utilities.debug
import net.corda.core.utilities.trace
import net.corda.node.services.api.FlowAppAuditEvent
import net.corda.node.services.api.FlowPermissionAuditEvent
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.logging.pushToLoggingContext
import net.corda.node.services.statemachine.transitions.FlowContinuation
import net.corda.node.services.statemachine.transitions.StateMachine
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseTransaction
import net.corda.nodeapi.internal.persistence.contextTransaction
import net.corda.nodeapi.internal.persistence.contextTransactionOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

class FlowPermissionException(message: String) : FlowException(message)

class TransientReference<out A>(@Transient val value: A)

class FlowStateMachineImpl<R>(override val id: StateMachineRunId,
                              override val logic: FlowLogic<R>,
                              scheduler: FiberScheduler
) : Fiber<Unit>(id.toString(), scheduler), FlowStateMachine<R>, FlowFiber {
    companion object {
        /**
         * Return the current [FlowStateMachineImpl] or null if executing outside of one.
         */
        fun currentStateMachine(): FlowStateMachineImpl<*>? = Strand.currentStrand() as? FlowStateMachineImpl<*>

        private val log: Logger = LoggerFactory.getLogger("net.corda.flow")
    }

    override val serviceHub get() = getTransientField(TransientValues::serviceHub)

    data class TransientValues(
            val eventQueue: Channel<Event>,
            val resultFuture: CordaFuture<Any?>,
            val database: CordaPersistence,
            val transitionExecutor: TransitionExecutor,
            val actionExecutor: ActionExecutor,
            val stateMachine: StateMachine,
            val serviceHub: ServiceHubInternal,
            val checkpointSerializationContext: SerializationContext
    )

    internal var transientValues: TransientReference<TransientValues>? = null
    internal var transientState: TransientReference<StateMachineState>? = null

    private fun <A> getTransientField(field: KProperty1<TransientValues, A>): A {
        val suppliedValues = transientValues ?: throw IllegalStateException("${field.name} wasn't supplied!")
        return field.get(suppliedValues.value)
    }

    private fun extractThreadLocalTransaction(): TransientReference<DatabaseTransaction> {
        val transaction = contextTransaction
        contextTransactionOrNull = null
        return TransientReference(transaction)
    }

    /**
     * Return the logger for this state machine. The logger name incorporates [id] and so including it in the log message
     * is not necessary.
     */
    override val logger = log
    override val resultFuture: CordaFuture<R> get() = uncheckedCast(getTransientField(TransientValues::resultFuture))
    override val context: InvocationContext get() = transientState!!.value.checkpoint.invocationContext
    override val ourIdentity: Party get() = transientState!!.value.checkpoint.ourIdentity
    internal var hasSoftLockedStates: Boolean = false
        set(value) {
            if (value) field = value else throw IllegalArgumentException("Can only set to true")
        }

    @Suspendable
    private fun processEvent(transitionExecutor: TransitionExecutor, event: Event): FlowContinuation {
        val stateMachine = getTransientField(TransientValues::stateMachine)
        val oldState = transientState!!.value
        val actionExecutor = getTransientField(TransientValues::actionExecutor)
        val transition = stateMachine.transition(event, oldState)
        val (continuation, newState) = transitionExecutor.executeTransition(this, oldState, event, transition, actionExecutor)
        transientState = TransientReference(newState)
        return continuation
    }

    @Suspendable
    private fun processEventsUntilFlowIsResumed(): Any? {
        val transitionExecutor = getTransientField(TransientValues::transitionExecutor)
        val eventQueue = getTransientField(TransientValues::eventQueue)
        eventLoop@while (true) {
            val nextEvent = eventQueue.receive()
            val continuation = processEvent(transitionExecutor, nextEvent)
            when (continuation) {
                is FlowContinuation.Resume -> return continuation.result
                is FlowContinuation.Throw -> {
                    continuation.throwable.fillInStackTrace()
                    throw continuation.throwable
                }
                FlowContinuation.ProcessEvents -> continue@eventLoop
                FlowContinuation.Abort -> abortFiber()
            }
        }
    }

    @Suspendable
    override fun run() {
        logic.stateMachine = this

        context.pushToLoggingContext()

        initialiseFlow()

        logger.debug { "Calling flow: $logic" }
        val startTime = System.nanoTime()
        val resultOrError = try {
            val result = logic.call()
            suspend(FlowIORequest.WaitForSessionConfirmations, maySkipCheckpoint = true)
            Try.Success(result)
        } catch (throwable: Throwable) {
            logger.warn("Flow threw exception", throwable)
            Try.Failure<R>(throwable)
        }
        val finalEvent = when (resultOrError) {
            is Try.Success -> {
                Event.FlowFinish(resultOrError.value)
            }
            is Try.Failure -> {
                Event.Error(resultOrError.exception)
            }
        }
        scheduleEvent(finalEvent)
        processEventsUntilFlowIsResumed()

        recordDuration(startTime)
    }

    @Suspendable
    private fun initialiseFlow() {
        processEventsUntilFlowIsResumed()
    }

    @Suspendable
    override fun <R> subFlow(subFlow: FlowLogic<R>): R {
        processEvent(getTransientField(TransientValues::transitionExecutor), Event.EnterSubFlow(subFlow.javaClass))
        return try {
            subFlow.call()
        } finally {
            processEvent(getTransientField(TransientValues::transitionExecutor), Event.LeaveSubFlow)
        }
    }

    @Suspendable
    override fun initiateFlow(party: Party): FlowSession {
        val resume = processEvent(
                getTransientField(TransientValues::transitionExecutor),
                Event.InitiateFlow(party)
        ) as FlowContinuation.Resume
        return resume.result as FlowSession
    }

    @Suspendable
    private fun abortFiber(): Nothing {
        while (true) {
            Fiber.park()
        }
    }

    // TODO Dummy implementation of access to application specific permission controls and audit logging
    override fun checkFlowPermission(permissionName: String, extraAuditData: Map<String, String>) {
        val permissionGranted = true // TODO define permission control service on ServiceHubInternal and actually check authorization.
        val checkPermissionEvent = FlowPermissionAuditEvent(
                serviceHub.clock.instant(),
                context,
                "Flow Permission Required: $permissionName",
                extraAuditData,
                logic.javaClass,
                id,
                permissionName,
                permissionGranted)
        serviceHub.auditService.recordAuditEvent(checkPermissionEvent)
        @Suppress("ConstantConditionIf")
        if (!permissionGranted) {
            throw FlowPermissionException("User ${context.principal()} not permissioned for $permissionName on flow $id")
        }
    }

    // TODO Dummy implementation of access to application specific audit logging
    override fun recordAuditEvent(eventType: String, comment: String, extraAuditData: Map<String, String>) {
        val flowAuditEvent = FlowAppAuditEvent(
                serviceHub.clock.instant(),
                context,
                comment,
                extraAuditData,
                logic.javaClass,
                id,
                eventType)
        serviceHub.auditService.recordAuditEvent(flowAuditEvent)
    }

    @Suspendable
    override fun flowStackSnapshot(flowClass: Class<out FlowLogic<*>>): FlowStackSnapshot? {
        return FlowStackSnapshotFactory.instance.getFlowStackSnapshot(flowClass)
    }

    override fun persistFlowStackSnapshot(flowClass: Class<out FlowLogic<*>>) {
        FlowStackSnapshotFactory.instance.persistAsJsonFile(flowClass, serviceHub.configuration.baseDirectory, id)
    }

    @Suspendable
    override fun <R : Any> suspend(ioRequest: FlowIORequest<R>, maySkipCheckpoint: Boolean): R {
        val serializationContext = TransientReference(getTransientField(TransientValues::checkpointSerializationContext))
        val transaction = extractThreadLocalTransaction()
        val transitionExecutor = TransientReference(getTransientField(TransientValues::transitionExecutor))
        parkAndSerialize { _, _ ->
            logger.trace { "Suspended on $ioRequest" }

            contextTransactionOrNull = transaction.value
            val event = try {
                Event.Suspend(
                        ioRequest = ioRequest,
                        maySkipCheckpoint = maySkipCheckpoint,
                        fiber = this.serialize(context = serializationContext.value)
                )
            } catch (throwable: Throwable) {
                Event.Error(throwable)
            }

            // We must commit the database transaction before returning from this closure, otherwise Quasar may schedule
            // other fibers
            require(processEvent(transitionExecutor.value, event) == FlowContinuation.ProcessEvents)
            Fiber.unparkDeserialized(this, scheduler)
        }
        return uncheckedCast(processEventsUntilFlowIsResumed())
    }

    @Suspendable
    override fun scheduleEvent(event: Event) {
        getTransientField(TransientValues::eventQueue).send(event)
    }

    override fun snapshot(): StateMachineState {
        return transientState!!.value
    }

    override val stateMachine get() = getTransientField(TransientValues::stateMachine)

    /**
     * Records the duration of this flow – from call() to completion or failure.
     * Note that the duration will include the time the flow spent being parked, and not just the total
     * execution time.
     */
    private fun recordDuration(startTime: Long, success: Boolean = true) {
        val timerName = "FlowDuration.${if (success) "Success" else "Failure"}.${logic.javaClass.name}"
        val timer = serviceHub.monitoringService.metrics.timer(timerName)
        // Start time gets serialized along with the fiber when it suspends
        val duration = System.nanoTime() - startTime
        timer.update(duration, TimeUnit.NANOSECONDS)
    }
}

val Class<out FlowLogic<*>>.flowVersionAndInitiatingClass: Pair<Int, Class<out FlowLogic<*>>>
    get() {
        var current: Class<*> = this
        var found: Pair<Int, Class<out FlowLogic<*>>>? = null
        while (true) {
            val annotation = current.getDeclaredAnnotation(InitiatingFlow::class.java)
            if (annotation != null) {
                if (found != null) throw IllegalArgumentException("${InitiatingFlow::class.java.name} can only be annotated once")
                require(annotation.version > 0) { "Flow versions have to be greater or equal to 1" }
                found = annotation.version to uncheckedCast(current)
            }
            current = current.superclass
                    ?: return found
                    ?: throw IllegalArgumentException("$name, as a flow that initiates other flows, must be annotated with " +
                    "${InitiatingFlow::class.java.name}. See https://docs.corda.net/api-flows.html#flowlogic-annotations.")
        }
    }

val Class<out FlowLogic<*>>.appName: String
    get() {
        val jarFile = Paths.get(protectionDomain.codeSource.location.toURI())
        return if (jarFile.isRegularFile() && jarFile.toString().endsWith(".jar")) {
            jarFile.fileName.toString().removeSuffix(".jar")
        } else {
            "<unknown>"
        }
    }
