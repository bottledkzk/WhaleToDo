package io.whaletodo.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.whaletodo.core.Window
import java.time.ZoneId
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.data.*
import io.whaletodo.app.reminder.ReminderCenter
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DeskState(
    val tasks: List<TaskRow> = emptyList(),
    val blocks: List<BlockRow> = emptyList(),
    val checkIns: List<CheckInRow> = emptyList(),
    val settings: SettingRow = SettingRow(),
    val workTimes: List<DailyWorkTimeRow> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val zoneId: String = ZoneId.systemDefault().id,
    val now: Long = System.currentTimeMillis(),
    val preferences: List<UiPreferenceRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeskViewModel(app: Application) : AndroidViewModel(app) {
    internal val repository = DeskRepository(DeskDatabase.open(app))
    internal val cloud = io.whaletodo.app.sync.AccountCloudController(repository,viewModelScope,
        onScheduleChanged={ReminderCenter.resyncAll(getApplication())})
    private val currentOwner = AgentBridgeHub.bridge.access
        .map { it.userId?.takeIf { owner -> owner.isNotBlank() } ?: "guest" }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "guest")
    val profile by lazy { io.whaletodo.app.profile.ProfileController(repository,viewModelScope,
        onRemindersChanged={ ReminderCenter.resyncAll(getApplication()) }) }
    val assistant by lazy { io.whaletodo.app.agent.AssistantController(repository, viewModelScope,
        onDiaryReady={owner,date,text,id -> profile.offerDiary(owner,date,text,id)},
        onScheduleChanged={ ReminderCenter.resyncAll(getApplication()) }) }
    private data class ClockState(val date: LocalDate, val zone: String, val now: Long)
    private fun clockState(): ClockState {
        val zone = ZoneId.systemDefault()
        return ClockState(LocalDate.now(zone), zone.id, System.currentTimeMillis())
    }
    private val clock = MutableStateFlow(clockState())
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshClock() }
    }
    fun refreshClock() { clock.value = clockState() }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(clockReceiver, filter)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                refreshClock()
            }
        }
    }

    val state =
        currentOwner
            .flatMapLatest { owner ->
                combine(
                    repository.dao.observeTasks(owner),
                    repository.dao.observeBlocks(owner),
                    repository.dao.observeCheckIns(owner),
                    repository.dao.observeSettings(owner),
                    repository.dao.observeWorkTimes(owner),
                ) { tasks, blocks, checks, settings, workTimes ->
                    DeskState(tasks, blocks, checks, settings ?: SettingRow(ownerId = owner), workTimes)
                }
                    .combine(repository.dao.observeOptions("device")) { state, options ->
                        state.copy(preferences = options)
                    }
            }
            .combine(clock) { state, time ->
                val zone=DeskOptions.zone(state.preferences)
                state.copy(today=java.time.Instant.ofEpochMilli(time.now).atZone(zone).toLocalDate(),zoneId=zone.id,now=time.now)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeskState())
    var preview = MutableStateFlow<PlanPreview?>(null)
        private set

    val message = MutableStateFlow("")
    val working = MutableStateFlow(false)

    private fun run(action: suspend () -> Unit) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            try {
                action()
            } catch (e: Exception) {
                message.value = e.message ?: "操作失败，请重试"
            } finally {
                working.value = false
            }
        }
    }

    fun capture(title: String) {
        val owner = currentOwner.value
        run {
            repository.capture(title, owner)
            message.value = "已记下"
        }
    }

    fun save(task: TaskRow) {
        val owner = currentOwner.value
        run {
            repository.save(task, owner)
            message.value = "已保存"
            runCatching { ReminderCenter.resyncAll(getApplication()) }
        }
    }

    fun saveScheduled(task: TaskRow, manual: Window?, reminder:TaskReminderRow?=null, onRejected: (String) -> Unit = {}, onSaved: () -> Unit = {}) {
        val owner = currentOwner.value
        run {
            try { repository.saveScheduled(task, manual, reminder, owner) }
            catch (e: IllegalArgumentException) { onRejected(e.message ?: "请检查安排"); return@run }
            message.value = "已保存安排"
            onSaved()
            runCatching { ReminderCenter.resyncAll(getApplication()) }
        }
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(clockReceiver)
        repository.close()
        super.onCleared()
    }

    fun complete(task: TaskRow, rating: Int) {
        val owner = currentOwner.value
        run {
            repository.complete(task, rating, owner)
            message.value = "今天又前进了一点"
            runCatching { ReminderCenter.resyncAll(getApplication()) }
        }
    }

    fun reopen(task: TaskRow) {
        val owner = currentOwner.value
        run {
            repository.reopen(task, owner)
            runCatching { ReminderCenter.resyncAll(getApplication()) }
        }
    }

    fun arrange() {
        val owner = currentOwner.value
        run { preview.value = repository.preview(owner) }
    }

    fun apply() {
        val owner = currentOwner.value
        run {
            preview.value?.let {
                repository.apply(it, owner)
                preview.value = null
                message.value = "计划已更新"
                runCatching { ReminderCenter.resyncAll(getApplication()) }
            }
        }
    }

    fun todayWorkTime(start: Int, end: Int, date: LocalDate) {
        val owner = currentOwner.value
        run {
            repository.todayWorkTime(start, end, date, owner)
            message.value = "今日 WorkTime 已更新"
        }
    }
}
