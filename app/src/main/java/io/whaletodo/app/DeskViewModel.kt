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
import io.whaletodo.app.data.*
import java.time.LocalDate
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
)

class DeskViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = DeskRepository(DeskDatabase.open(app))
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
        combine(
                repository.dao.observeTasks(),
                repository.dao.observeBlocks(),
                repository.dao.observeCheckIns(),
                repository.dao.observeSettings(),
                repository.dao.observeWorkTimes(),
            ) { tasks, blocks, checks, settings, workTimes ->
                DeskState(tasks, blocks, checks, settings ?: SettingRow(), workTimes)
            }
            .combine(clock) { state, time -> state.copy(today = time.date, zoneId = time.zone, now = time.now) }
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

    fun capture(title: String) = run {
        repository.capture(title)
        message.value = "已记下"
    }

    fun save(task: TaskRow) = run {
        repository.save(task)
        message.value = "已保存"
    }

    fun saveScheduled(task: TaskRow, manual: Window?, onRejected: (String) -> Unit = {}, onSaved: () -> Unit = {}) = run {
        try { repository.saveScheduled(task, manual) }
        catch (e: IllegalArgumentException) { onRejected(e.message ?: "请检查安排"); return@run }
        message.value = "已保存安排"
        onSaved()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(clockReceiver)
        super.onCleared()
    }

    fun complete(task: TaskRow, rating: Int) = run {
        repository.complete(task, rating)
        message.value = "今天又前进了一点"
    }

    fun reopen(task: TaskRow) = run { repository.reopen(task) }

    fun arrange() = run { preview.value = repository.preview() }

    fun apply() = run {
        preview.value?.let {
            repository.apply(it)
            preview.value = null
            message.value = "计划已更新"
        }
    }

    fun todayWorkTime(start: Int, end: Int, date: LocalDate) = run {
        repository.todayWorkTime(start, end, date)
        message.value = "今日 WorkTime 已更新"
    }
}
