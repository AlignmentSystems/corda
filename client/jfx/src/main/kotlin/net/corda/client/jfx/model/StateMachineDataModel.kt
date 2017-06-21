package net.corda.client.jfx.model

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import net.corda.client.jfx.utils.fold
import net.corda.client.jfx.utils.map
import net.corda.client.jfx.utils.recordAsAssociation
import net.corda.core.ErrorOr
import net.corda.core.flows.FlowInitiator
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.StateMachineUpdate
import org.fxmisc.easybind.EasyBind

data class ProgressStatus(val status: String?)

sealed class StateMachineStatus {
    data class Added(val id: StateMachineRunId, val stateMachineName: String, val flowInitiator: FlowInitiator) : StateMachineStatus()
    data class Removed(val id: StateMachineRunId, val result: ErrorOr<*>) : StateMachineStatus()
}

data class StateMachineData(
        val id: StateMachineRunId,
        val stateMachineName: String,
        val flowInitiator: FlowInitiator,
        val smmStatus: Pair<ObservableValue<StateMachineStatus>, ObservableValue<ProgressStatus>>
)

data class Counter(
        var errored: SimpleIntegerProperty = SimpleIntegerProperty(0),
        var success: SimpleIntegerProperty = SimpleIntegerProperty(0),
        var progress: SimpleIntegerProperty = SimpleIntegerProperty(0)
) {
    fun addSmm() { progress.value += 1 }
    fun removeSmm(result: ErrorOr<*>) {
        progress.value -= 1
        when (result.error) {
            null -> success.value += 1
            else -> errored.value += 1
        }
    }
}

class StateMachineDataModel {
    private val stateMachineUpdates by observable(NodeMonitorModel::stateMachineUpdates)
    private val progressTracking by observable(NodeMonitorModel::progressTracking)
    private val progressEvents = progressTracking.recordAsAssociation(ProgressTrackingEvent::stateMachineId)

    val counter = Counter()

    private val stateMachineIndexMap = HashMap<StateMachineRunId, Int>()
    private val stateMachineStatus = stateMachineUpdates.fold(FXCollections.observableArrayList<SimpleObjectProperty<StateMachineStatus>>()) { list, update ->
        when (update) {
            is StateMachineUpdate.Added -> {
                counter.addSmm()
                val flowInitiator= update.stateMachineInfo.initiator
                val added: SimpleObjectProperty<StateMachineStatus> =
                        SimpleObjectProperty(StateMachineStatus.Added(update.id, update.stateMachineInfo.flowLogicClassName, flowInitiator))
                list.add(added)
                stateMachineIndexMap[update.id] = list.size - 1
            }
            is StateMachineUpdate.Removed -> {
                val addedIdx = stateMachineIndexMap[update.id]
                val added = addedIdx?.let { list.getOrNull(addedIdx) }
                added ?: throw Exception("State machine removed with unknown id ${update.id}")
                counter.removeSmm(update.result)
                list[addedIdx].set(StateMachineStatus.Removed(update.id, update.result))
            }
        }
    }

    private val stateMachineDataList = stateMachineStatus.map {
        val smStatus = it.value as StateMachineStatus.Added
        val id = smStatus.id
        val progress = SimpleObjectProperty(progressEvents.get(id))
        StateMachineData(id, smStatus.stateMachineName, smStatus.flowInitiator,
                Pair(it, EasyBind.map(progress) { ProgressStatus(it?.message) }))
    }

    val stateMachinesAll = stateMachineDataList
    val error = counter.errored
    val success = counter.success
    val progress = counter.progress
}
