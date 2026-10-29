package io.whaletodo.app.agent

import android.os.SystemClock
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EditPreview(val id: String, val owner: String, val source: DeskSnapshot, val changes: List<AgentChange>, val modelCode: String)
data class AssistantState(val owner: String = "guest", val access: AgentAccess = AgentAccess(),
    val sessionId: String = UUID.randomUUID().toString(), val title: String = "新对话",
    val mode: AgentMode = AgentMode.CHAT, val model: AgentModel = AgentModel("unavailable","鲸鱼AI"),
    val models: List<AgentModel> = emptyList(), val shareStyle: String = "minimal", val includeAgenda: Boolean = true, val editConsent: Boolean = false,
    val messages: List<AgentMessageRow> = emptyList(), val sessions: List<AgentSessionRow> = emptyList(),
    val pendingId: String? = null, val busySince: Long? = null, val gate: GateReason? = null, val notice: String = "", val preview: EditPreview? = null, val revision: EditPreview? = null)

class AssistantController(private val repo: DeskRepository, private val scope: CoroutineScope,
    private val bridge: AgentBridge = AgentBridgeHub.bridge,
    private val onDiaryReady:(String,String,String,String)->Unit={_,_,_,_->},
    private val onScheduleChanged:suspend ()->Unit={}) {
    private val mutable = MutableStateFlow(AssistantState(models=bridge.models))
    val state: StateFlow<AssistantState> = mutable.asStateFlow()
    init { scope.launch { bridge.access.collect { access ->
        val owner = access.userId ?: "guest"
        if(owner != mutable.value.owner) mutable.value=AssistantState(owner=owner,access=access,models=bridge.models,model=AgentPolicy.route(bridge.models,access,AgentMode.CHAT))
        else mutable.update { it.copy(access=access,models=bridge.models,model=AgentPolicy.route(bridge.models,access,it.mode)) }
        refreshHistory()
    } } }
    private suspend fun refreshHistory() {
        val owner=mutable.value.owner;val rows=repo.dao.sessions(owner)
        mutable.update { if(it.owner==owner) it.copy(sessions=rows) else it }
    }
    fun newSession() { if(mutable.value.busySince!=null)return
        mutable.update { AssistantState(owner=it.owner,access=it.access,models=bridge.models,mode=it.mode,model=it.model,includeAgenda=it.includeAgenda) }
    }
    fun mode(mode: AgentMode) { if(mutable.value.busySince==null) mutable.update { it.copy(mode=mode,model=AgentPolicy.route(bridge.models,it.access,mode),editConsent=false,preview=null,revision=null) } }
    fun style(style: String) { if(ShareStyle.entries.any{it.code==style}) mutable.update {it.copy(shareStyle=style)} }
    fun agenda(enabled:Boolean) { mutable.update { it.copy(includeAgenda=enabled,preview=null) } }
    fun consent(enabled:Boolean) { mutable.update { it.copy(editConsent=enabled,preview=null) } }
    fun dismissGate(){ mutable.update { it.copy(gate=null) } }
    fun dismissPreview(){mutable.update { it.copy(preview=null,revision=null,editConsent=false,notice="已保留原日程") }}
    fun requestRevision(){mutable.update { it.copy(revision=it.preview,preview=null,editConsent=false) }}
    fun dismissRevision(){mutable.update { it.copy(revision=null,editConsent=false,notice="已保留原日程") }}
    fun revisePreview(opinion:String) {
        val s=mutable.value;val proposal=s.revision?:return
        if(opinion.isBlank()||opinion.length>2000||s.owner!=proposal.owner||s.mode!=AgentMode.EDIT)return
        send("意见：${opinion.trim()}",proposal)
    }
    fun refresh(){scope.launch { refreshHistory() }}
    fun openSession(row: AgentSessionRow) { scope.launch {
        if(mutable.value.busySince!=null||row.ownerId!=mutable.value.owner)return@launch
        val messages=repo.dao.messages(row.id,row.ownerId)
        mutable.update { if(it.owner==row.ownerId)it.copy(sessionId=row.id,title=row.title,messages=messages,preview=null,revision=null,editConsent=false,
            pendingId=messages.lastOrNull{m->m.role=="user"&&m.status=="pending"}?.id,
            mode=AgentMode.entries.find{m->m.code==messages.lastOrNull{it.role=="user"}?.mode}?:it.mode,
            model=AgentPolicy.route(bridge.models,it.access,AgentMode.entries.find{m->m.code==messages.lastOrNull{it.role=="user"}?.mode}?:it.mode)) else it }
    } }
    fun archive(row:AgentSessionRow) { scope.launch {
        if(mutable.value.busySince!=null)return@launch
        repo.dao.archiveSession(row.id,mutable.value.owner);if(row.id==mutable.value.sessionId)newSession();refreshHistory()
    } }
    private suspend fun saveMessage(row: AgentMessageRow, title:String) {
        val prior=repo.dao.sessions(row.ownerId).find { it.id==row.sessionId }
        repo.dao.save(AgentSessionRow(row.sessionId,row.ownerId,prior?.title?:title.take(28),prior?.created?:row.created,row.created))
        repo.dao.addMessage(row)
    }

    private fun hash(snapshot:DeskSnapshot):String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(snapshot.toString().toByteArray()).joinToString(""){"%02x".format(it)}
    private suspend fun receive(sent:AgentMessageRow,source:DeskSnapshot?,reply:AssistantReply) {
        if(mutable.value.owner!=sent.ownerId||mutable.value.sessionId!=sent.sessionId)return
        require(reply.text.isNotBlank()) { "助手暂未返回可用内容" }
        val answer=AgentMessageRow(sent.id+":answer",sent.sessionId,sent.ownerId,"assistant",sent.mode,AssistantIdentity.display(reply.text),
            System.currentTimeMillis(),reply.totalTokens,reply.inputTokens,reply.outputTokens,reply.chargedPoints)
        if(repo.dao.messages(sent.sessionId,sent.ownerId).none{it.id==answer.id})saveMessage(answer,sent.text)
        repo.dao.requestStatus(sent.id,sent.ownerId,"complete")
        val model=bridge.models.find{it.code==sent.modelCode}
        var canPreview=reply.changes.isNotEmpty()&&model!=null&&sent.mode==AgentMode.EDIT.code&&sent.editGranted&&source!=null&&
            sent.snapshotHash==hash(source)&&AgentPolicy.proposalGate(mutable.value.access,model!!)==null
        if(canPreview)canPreview=runCatching{repo.checkAgentPreview(source!!,reply.changes,sent.ownerId)}.isSuccess
        val rows=repo.dao.messages(sent.sessionId,sent.ownerId)
        mutable.update {if(it.owner!=sent.ownerId||it.sessionId!=sent.sessionId)it else it.copy(messages=rows,pendingId=null,
            preview=if(canPreview)EditPreview(sent.id,sent.ownerId,source!!,reply.changes,model!!.code)else null,
            editConsent=false,revision=null,
            notice=if(reply.changes.isNotEmpty()&&!canPreview)"原提案已失效，可重新生成" else if(reply.continuation)"本次内容较长，可缩小范围后继续" else "")}
        if(sent.mode==AgentMode.DIARY.code&&mutable.value.owner==sent.ownerId&&mutable.value.sessionId==sent.sessionId){
            val diary=DiaryContent.fromAssistant(reply.text)
            if(diary!=null)onDiaryReady(sent.ownerId,source?.date?.toString()?:LocalDate.now().toString(),diary,sent.id)
            else mutable.update{it.copy(notice="日记草稿暂不可用，请缩小范围后重新生成")}
        }
        refreshHistory()
    }
    private suspend fun failed(sent:AgentMessageRow,error:Exception,querying:Boolean=false) {
        if(mutable.value.owner!=sent.ownerId||mutable.value.sessionId!=sent.sessionId)return
        val definite=(!querying&&error is AgentGateException) || error is AgentServiceException&&error.definitelyRejected
        if(definite)repo.dao.requestStatus(sent.id,sent.ownerId,"failed")
        mutable.update {if(it.owner!=sent.ownerId||it.sessionId!=sent.sessionId)it else it.copy(pendingId=if(definite)null else sent.id,
            gate=(error as? AgentGateException)?.reason,
            notice=if(definite)"本次未完成，可调整后再试" else "请求状态待确认，请查询结果；不会自动重复调用")}
    }
    fun send(draft:String, revision:EditPreview?=null) {
        val source=mutable.value
        if(source.busySince!=null||source.pendingId!=null||draft.isBlank())return
        if(source.mode.edits&&!source.includeAgenda){mutable.update{it.copy(notice="请开启日程读取后生成提案")};return}
        val gate=AgentPolicy.gate(source.access,source.mode,source.model,source.editConsent)
        if(gate!=null){mutable.update{it.copy(gate=gate)};return}
        mutable.update{it.copy(busySince=SystemClock.elapsedRealtime(),notice="",preview=null,revision=null)}
        scope.launch {
            var sent:AgentMessageRow?=null
            try {
                val snapshot=repo.snapshot(source.owner)
                // Feedback uses a new request ID; machine context is not rendered as user chat text.
                val prompt=if(revision==null)draft.trim() else org.json.JSONObject()
                    .put("instruction","原提案未采纳，请结合本次意见生成新的待确认提案")
                    .put("unaccepted_proposal",org.json.JSONArray(revision.lines()))
                    .put("unaccepted_changes",org.json.JSONArray(revision.changes.map{change->org.json.JSONObject().apply{when(change){
                        is AgentChange.RenameTask->{put("type","rename_task");put("target_id",change.taskId);put("title",change.title)}
                        is AgentChange.CategorizeTask->{put("type","set_category");put("target_id",change.taskId);put("category",change.category)}
                        is AgentChange.MoveBlock->{put("type","move_block");put("target_id",change.blockId);put("start_ms",change.start);put("end_ms",change.end)}
                    }}}))
                    .put("feedback",draft.removePrefix("意见：")).toString()
                val context=if(!source.includeAgenda)"" else if(source.mode==AgentMode.DIARY||source.mode==AgentMode.REVIEW)
                    reflectionFacts(snapshot,repo.dao.checkIns(source.owner),source.owner,source.mode==AgentMode.REVIEW) else agendaText(snapshot)
                val request=AssistantRequest(UUID.randomUUID().toString(),source.mode.code,source.model.code,prompt,
                    context,source.editConsent,source.shareStyle,
                    source.messages.filter{it.status=="complete"}.takeLast(12).map{it.role to it.text},source.owner)
                sent=AgentMessageRow(request.id,source.sessionId,source.owner,"user",source.mode.code,draft.trim(),
                    System.currentTimeMillis(),status="pending",snapshotHash=hash(snapshot),modelCode=source.model.code,editGranted=source.editConsent)
                saveMessage(sent,draft)
                if(mutable.value.owner!=source.owner)return@launch
                mutable.update{it.copy(messages=it.messages+sent,title=if(it.title=="新对话")draft.take(28)else it.title)}
                check(bridge.access.value.userId==source.access.userId) { "账户已切换" }
                val reply=withTimeout(60_000){bridge.reply(request)}
                receive(sent,snapshot,reply)
            }catch(e:CancellationException){if(e is TimeoutCancellationException)sent?.let{failed(it,e)} else throw e}
            catch(e:Exception){sent?.let{failed(it,e)}?:mutable.update{it.copy(notice="暂时无法读取日程")}}
            finally {mutable.update{if(it.owner==source.owner&&it.sessionId==source.sessionId)it.copy(busySince=null)else it}}
        }
    }
    fun queryPending() {
        val source=mutable.value;val id=source.pendingId?:return
        if(source.busySince!=null)return
        if(source.access.userId==null){mutable.update{it.copy(gate=GateReason.LOGIN)};return}
        mutable.update{it.copy(busySince=SystemClock.elapsedRealtime())}
        scope.launch {
            val sent=repo.dao.messages(source.sessionId,source.owner).find{it.id==id}
            if(sent==null){mutable.update{it.copy(busySince=null,pendingId=null)};return@launch}
            if(mutable.value.owner!=source.owner||mutable.value.sessionId!=source.sessionId)return@launch
            mutable.update{it.copy(busySince=SystemClock.elapsedRealtime())}
            try{val reply=withTimeout(60_000){bridge.query(id)};receive(sent,repo.snapshot(source.owner),reply)}
            catch(e:CancellationException){if(e is TimeoutCancellationException)failed(sent,e,true)else throw e}
            catch(e:Exception){failed(sent,e,true)}
            finally{mutable.update{if(it.owner==source.owner&&it.sessionId==source.sessionId)it.copy(busySince=null)else it}}
        }
    }
    fun applyPreview() {
        val preview=mutable.value.preview?:return
        val current=mutable.value
        val model=bridge.models.find{it.code==preview.modelCode}
        val gate=if(model==null)GateReason.MODEL else AgentPolicy.proposalGate(current.access,model)
        if(gate!=null){mutable.update{it.copy(gate=gate)};return}
        if(current.owner!=preview.owner)return
        if(current.busySince!=null)return
        mutable.update{it.copy(busySince=SystemClock.elapsedRealtime())}
        scope.launch {try {
            check(mutable.value.owner==preview.owner) { "账户已切换" }
            repo.applyAgent(preview.id,preview.owner,preview.source,preview.changes)
            runCatching{onScheduleChanged()}
            mutable.update {if(it.owner==preview.owner)it.copy(preview=null,notice="已应用提案")else it}
        }catch(e:Exception){mutable.update {if(it.owner==preview.owner)it.copy(preview=null,notice="提案已失效，请重新生成")else it}}
        finally{mutable.update{if(it.owner==preview.owner)it.copy(busySince=null)else it}} }
    }
}

/** All modes consume the same read-only copy; notes, credentials and account data are excluded. */
/** A task's estimate and done flag are not evidence of actual duration or the completion day. */
internal fun reflectionFacts(s:DeskSnapshot,records:List<CheckInRow>,owner:String,includeUnfinished:Boolean):String {
    val zone=ZoneId.of(s.zoneId)
    val tasks=s.tasks.filter{it.ownerId==owner}.associateBy{it.id}
    val completed=records.filter{it.ownerId==owner&&Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate()==s.date&&tasks.containsKey(it.taskId)}
        .sortedByDescending{it.completedAt}.distinctBy{it.taskId}
    val unfinished=tasks.values.filter{!it.done}
    return org.json.JSONObject().put("date",s.date.toString()).put("timezone",s.zoneId)
        .put("completion_date_known",true).put("actual_duration_recorded",false)
        .put("completed",org.json.JSONArray(completed.take(40).map{record->org.json.JSONObject()
            .put("id",record.taskId).put("title",tasks.getValue(record.taskId).title)
            .put("completed_at",Instant.ofEpochMilli(record.completedAt).toString())}))
        .put("unfinished",org.json.JSONArray(if(includeUnfinished)unfinished.take(30).map{task->org.json.JSONObject()
            .put("id",task.id).put("title",task.title).put("estimated_minutes",task.minutes)}else emptyList<org.json.JSONObject>()))
        .put("partial",completed.size>40||(includeUnfinished&&unfinished.size>30)).toString()
}

internal fun agendaText(s:DeskSnapshot):String=buildString {
    append("时区=${s.zoneId}; 日期=${s.date}; 默认可用=${DeskOptions.defaults(s.preferences,s.settings)}; 星期时段=${DeskOptions.weekdays(s.preferences)}\n")
    val ordered=s.tasks.sortedWith(compareBy<TaskRow>{it.done}.thenBy{if(it.deadline>0)it.deadline else Long.MAX_VALUE})
    ordered.take(100).forEach {t->
        append("task id=${t.id} 标题=${t.title.replace('\n',' ').replace('\r',' ')} 类型=${t.category} 重要度=${t.importance} 工时=${t.minutes}min 截止=${if(t.deadline>0)Instant.ofEpochMilli(t.deadline)else "无"} 完成=${t.done}\n")
    }
    s.blocks.sortedBy{it.start}.take(100).forEach {b->append("block id=${b.id} task=${b.taskId} 开始=${Instant.ofEpochMilli(b.start)} 结束=${Instant.ofEpochMilli(b.end)} 固定=${b.locked} 完成=${b.done}\n")}
    s.workTimes.take(14).forEach {append("空闲 ${it.date} ${it.startMinute}-${it.endMinute}min\n")}
    if(ordered.size>100||s.blocks.size>100)append("其余条目未附带；请先缩小范围。\n")
}

/** Human-readable before/after facts taken from the exact proposal snapshot. */
internal fun EditPreview.lines():List<String> = changes.map { c ->
    when(c) {
        is AgentChange.RenameTask -> "${source.tasks.find{it.id==c.taskId}?.title.orEmpty()} → ${c.title}"
        is AgentChange.CategorizeTask -> "${source.tasks.find{it.id==c.taskId}?.title.orEmpty()}：${TaskCategory.from(c.category).label}"
        is AgentChange.MoveBlock -> {
            val block=source.blocks.find{it.id==c.blockId}
            val title=source.tasks.find{it.id==block?.taskId}?.title.orEmpty()
            val format=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of(source.zoneId))
            "$title：${format.format(Instant.ofEpochMilli(c.start))} — ${format.format(Instant.ofEpochMilli(c.end))}"
        }
    }
}
