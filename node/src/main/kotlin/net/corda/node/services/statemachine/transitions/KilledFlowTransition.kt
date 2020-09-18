package net.corda.node.services.statemachine.transitions

import net.corda.core.flows.FlowException
import net.corda.core.flows.KilledFlowException
import net.corda.node.services.messaging.MessageIdentifier
import net.corda.node.services.statemachine.Action
import net.corda.node.services.statemachine.ErrorSessionMessage
import net.corda.node.services.statemachine.Event
import net.corda.node.services.statemachine.FlowError
import net.corda.node.services.statemachine.FlowRemovalReason
import net.corda.node.services.statemachine.MessageType
import net.corda.node.services.statemachine.SessionId
import net.corda.node.services.statemachine.SessionState
import net.corda.node.services.statemachine.StateMachineState

class KilledFlowTransition(
    override val context: TransitionContext,
    override val startingState: StateMachineState,
    val event: Event
) : Transition {

    override fun transition(): TransitionResult {
        return builder {

            val killedFlowError = createKilledFlowError()
            val killedFlowErrorMessage = createErrorMessageFromError(killedFlowError)
            val errorMessages = listOf(killedFlowErrorMessage)

            val (initiatedSessions, newSessionStates) = bufferErrorMessagesInInitiatingSessions(
                startingState.checkpoint.checkpointState.sessions,
                errorMessages
            )
            val sessionsWithAdvancedSeqNumbers = mutableMapOf<SessionId, SessionState>()
            val errorsPerSession = initiatedSessions.map { (sessionId, sessionState) ->
                var currentSeqNumber = sessionState.nextSendingSeqNumber
                val errorsWithId = errorMessages.map { errorMsg ->
                    val messageIdentifier = MessageIdentifier(MessageType.SESSION_ERROR, sessionState.shardId, sessionState.peerSinkSessionId, currentSeqNumber, startingState.checkpoint.checkpointState.suspensionTime)
                    currentSeqNumber++
                    Pair(messageIdentifier, errorMsg)
                }.toList()
                sessionsWithAdvancedSeqNumbers[sessionId] = sessionState.copy(nextSendingSeqNumber = currentSeqNumber)
                Pair(sessionState, errorsWithId)
            }.toMap()


            currentState = currentState.copy(
                    checkpoint = startingState.checkpoint.setSessions(sessions = newSessionStates + sessionsWithAdvancedSeqNumbers),
                    pendingDeduplicationHandlers = emptyList(),
                    closedSessionsPendingToBeSignalled = emptyMap(),
                    isRemoved = true
            )
            actions += Action.PropagateErrors(errorsPerSession, startingState.senderUUID)

            if (!startingState.isFlowResumed) {
                actions += Action.CreateTransaction
            }
            // The checkpoint and soft locks are also removed directly in [StateMachineManager.killFlow]
            if (startingState.isAnyCheckpointPersisted) {
                actions += Action.RemoveCheckpoint(context.id, mayHavePersistentResults = true)
            }
            val signalSessionsEndMap = currentState.checkpoint.checkpointState.sessions.map { (sessionId, sessionState) ->
                sessionId to Pair(sessionState.lastSenderUUID, sessionState.lastSenderSeqNo)
            }.toMap()

            actions += Action.PersistDeduplicationFacts(startingState.pendingDeduplicationHandlers)
            actions += Action.SignalSessionsHasEnded(signalSessionsEndMap)
            actions += Action.ReleaseSoftLocks(context.id.uuid)
            actions += Action.CommitTransaction(currentState)
            actions += Action.AcknowledgeMessages(startingState.pendingDeduplicationHandlers)
            actions += Action.RemoveSessionBindings(startingState.checkpoint.checkpointState.sessions.keys)
            actions += Action.RemoveFlow(context.id, createKilledRemovalReason(killedFlowError), currentState)

            FlowContinuation.Abort
        }
    }

    private fun createKilledFlowError(): FlowError {
        val exception = when (event) {
            is Event.Error -> event.exception
            else -> KilledFlowException(context.id)
        }
        return FlowError(context.secureRandom.nextLong(), exception)
    }

    // Purposely left the same as [bufferErrorMessagesInInitiatingSessions] in [ErrorFlowTransition] so that it can be refactored
    private fun createErrorMessageFromError(error: FlowError): ErrorSessionMessage {
        val exception = error.exception
        // If the exception doesn't contain an originalErrorId that means it's a fresh FlowException that should
        // propagate to the neighbouring flows. If it has the ID filled in that means it's a rethrown FlowException and
        // shouldn't be propagated.
        return if (exception is FlowException && exception.originalErrorId == null) {
            ErrorSessionMessage(flowException = exception, errorId = error.errorId)
        } else {
            ErrorSessionMessage(flowException = null, errorId = error.errorId)
        }
    }

    /**
     * Buffers errors message for initiating states and filters the initiated states.
     * Returns a pair that consists of:
     * - a map containing the initiated states as filtered from the ones provided as input.
     * - a map containing the new state of all the sessions.
     */
    private fun bufferErrorMessagesInInitiatingSessions(
        sessions: Map<SessionId, SessionState>,
        errorMessages: List<ErrorSessionMessage>
    ): Pair<Map<SessionId, SessionState.Initiated>, Map<SessionId, SessionState>> {
        val newSessions = sessions.mapValues { (sourceSessionId, sessionState) ->
            if (sessionState is SessionState.Initiating && sessionState.rejectionError == null) {
                var currentSequenceNumber = sessionState.nextSendingSeqNumber
                val errorMessagesWithDeduplication = errorMessages.map { errorMessage ->
                    val messageIdentifier = MessageIdentifier(MessageType.SESSION_ERROR, sessionState.shardId, sourceSessionId.calculateInitiatedSessionId(), currentSequenceNumber, startingState.checkpoint.checkpointState.suspensionTime)
                    currentSequenceNumber++
                    messageIdentifier to errorMessage
                }
                sessionState.bufferMessages(errorMessagesWithDeduplication)
            } else {
                sessionState
            }
        }
        // if we have already received error message from the other side, we don't include that session in the list to avoid propagating errors.
        val initiatedSessions = sessions.mapNotNull { (sessionId, sessionState) ->
            if (sessionState is SessionState.Initiated && !sessionState.otherSideErrored) {
                sessionId to sessionState
            } else {
                null
            }
        }.toMap()
        return Pair(initiatedSessions, newSessions)
    }

    private fun createKilledRemovalReason(error: FlowError): FlowRemovalReason.ErrorFinish {
        return FlowRemovalReason.ErrorFinish(listOf(error))
    }
}