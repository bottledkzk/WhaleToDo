package io.whaletodo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.whaletodo.app.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DeskState(val tasks: List<TaskRow> = emptyList(),val blocks: List<BlockRow> = emptyList(),val checkIns: List<CheckInRow> = emptyList(),val settings: SettingRow = SettingRow())
class DeskViewModel(app: Application): AndroidViewModel(app) {
    private val repository=DeskRepository(DeskDatabase.open(app))
    val state=combine(repository.dao.observeTasks(),repository.dao.observeBlocks(),repository.dao.observeCheckIns(),repository.dao.observeSettings()) { tasks,blocks,checks,settings -> DeskState(tasks,blocks,checks,settings?:SettingRow()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),DeskState())
    var preview=MutableStateFlow<PlanPreview?>(null); private set
    val message=MutableStateFlow("")
    val working=MutableStateFlow(false)
    private fun run(action: suspend () -> Unit) { if (working.value) return;viewModelScope.launch { working.value=true;try { action() } catch (e: Exception) { message.value=e.message?:"操作失败，请重试" } finally { working.value=false } } }
    fun capture(title: String)=run { repository.capture(title);message.value="已记下" }
    fun save(task: TaskRow)=run { repository.save(task);message.value="已保存" }
    fun complete(task: TaskRow,rating: Int)=run { repository.complete(task,rating);message.value="今天又前进了一点" }
    fun reopen(task: TaskRow)=run { repository.reopen(task) }
    fun arrange()=run { preview.value=repository.preview() }
    fun apply()=run { preview.value?.let { repository.apply(it);preview.value=null;message.value="计划已更新" } }
    fun settings(start: Int,end: Int)=run { repository.settings(start,end);message.value="空闲时间已更新" }
}
