package io.whaletodo.app.sync

import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Only the schedule domain crosses this boundary; chat, diary, identity and credentials cannot. */
internal data class ScheduleBackup(
 val tasks:List<TaskRow>,val blocks:List<BlockRow>,val checks:List<CheckInRow>,
 val workTimes:List<DailyWorkTimeRow>,val settings:SettingRow,val reminders:List<TaskReminderRow>,
) {
 fun json():String=JSONObject().put("schema_version",1)
  .put("tasks",array(tasks.sortedBy{it.id}) {t->JSONObject().put("id",t.id).put("title",t.title).put("minutes",t.minutes).put("deadline",t.deadline).put("importance",t.importance).put("done",t.done).put("created",t.created).put("note",t.note).put("chunkMinutes",t.chunkMinutes).put("splittable",t.splittable).put("category",t.category)})
  .put("blocks",array(blocks.sortedBy{it.id}) {b->JSONObject().put("id",b.id).put("taskId",b.taskId).put("start",b.start).put("end",b.end).put("locked",b.locked).put("done",b.done)})
  .put("checkins",array(checks.sortedBy{it.id}) {c->JSONObject().put("id",c.id).put("taskId",c.taskId).put("completedAt",c.completedAt).put("rating",c.rating)})
  .put("work_times",array(workTimes.sortedBy{it.date}) {w->JSONObject().put("date",w.date).put("startMinute",w.startMinute).put("endMinute",w.endMinute)})
  .put("settings",JSONObject().put("startHour",settings.startHour).put("endHour",settings.endHour))
  .put("reminders",array(reminders.sortedBy{it.taskId}) {r->JSONObject().put("taskId",r.taskId).put("enabled",r.enabled).put("method",r.method).put("timing",r.timing)}).toString()
 fun validate(){
  require(listOf(tasks.size,blocks.size,checks.size,workTimes.size,reminders.size).all{it<=10000})
  require(tasks.map{it.id}.distinct().size==tasks.size&&blocks.map{it.id}.distinct().size==blocks.size&&checks.map{it.id}.distinct().size==checks.size)
  require(workTimes.map{it.date}.distinct().size==workTimes.size&&reminders.map{it.taskId}.distinct().size==reminders.size)
  tasks.forEach{require(it.id.isNotBlank()&&it.id.length<=64&&it.title.isNotBlank()&&it.title.length<=160&&it.note.length<=4096&&it.minutes in 0..525600&&it.chunkMinutes in 1..525600&&it.importance in 1..5&&it.deadline>=0&&it.created>=0&&TaskCategory.entries.any{c->c.code==it.category})}
  blocks.forEach{b->require(b.start>=0&&b.end>b.start&&tasks.any{it.id==b.taskId})}
  checks.forEach{require(it.rating in 1..5&&it.completedAt>=0&&it.id.isNotBlank())}
  workTimes.forEach{LocalDate.parse(it.date);MinuteRange(it.startMinute,it.endMinute)}
  require(settings.startHour in 0..23&&settings.endHour in 1..24&&settings.endHour>settings.startHour)
  reminders.forEach{r->require(tasks.any{it.id==r.taskId}&&ReminderMethod.entries.any{it.code==r.method}&&ReminderTiming.entries.any{it.code==r.timing})}
 }
 /** Local objects win by ID; conflicting cloud blocks stay unscheduled rather than overlap. */
 fun compatible(cloud:ScheduleBackup):Pair<ScheduleBackup,Int>{
  val localTaskIds=tasks.map{it.id}.toSet();val allTasks=tasks+cloud.tasks.filter{it.id !in localTaskIds}
  val resultBlocks=blocks.toMutableList();var skipped=0
  cloud.blocks.filter{it.taskId !in localTaskIds&&blocks.none{b->b.id==it.id}}.forEach{b->
   if(resultBlocks.any{it.start<b.end&&b.start<it.end})skipped++ else resultBlocks+=b
  }
  val taskIds=allTasks.map{it.id}.toSet()
  return copy(tasks=allTasks,blocks=resultBlocks,checks=checks+cloud.checks.filter{c->checks.none{it.id==c.id}},
   workTimes=workTimes+cloud.workTimes.filter{w->workTimes.none{it.date==w.date}},
   reminders=reminders+cloud.reminders.filter{r->r.taskId in taskIds&&r.taskId !in localTaskIds&&reminders.none{it.taskId==r.taskId}}) to skipped
 }
 companion object {
  private fun <T> array(items:List<T>,make:(T)->JSONObject)=JSONArray().apply{items.forEach{put(make(it))}}
  suspend fun capture(repo:DeskRepository,owner:String)=repo.transaction{
   ScheduleBackup(repo.dao.tasks(owner),repo.dao.blocks(owner),repo.dao.checkIns(owner),repo.dao.workTimes(owner),repo.dao.settings(owner)?:SettingRow(ownerId=owner),repo.dao.reminders(owner))
  }
  fun parse(raw:String,owner:String):ScheduleBackup{
   require(raw.toByteArray().size<=1_500_000)
   val p=JSONObject(raw);require(p.getInt("schema_version")==1)
   fun <T> rows(key:String,make:(JSONObject)->T):List<T>{val a=p.getJSONArray(key);require(a.length()<=10000);return (0 until a.length()).map{make(a.getJSONObject(it))}}
   return ScheduleBackup(
    rows("tasks"){t->TaskRow(t.getString("id"),t.getString("title"),t.getInt("minutes"),t.getLong("deadline"),t.getInt("importance"),t.getBoolean("done"),t.getLong("created"),t.getString("note"),t.getInt("chunkMinutes"),t.getBoolean("splittable"),t.getString("category"),owner)},
    rows("blocks"){b->BlockRow(b.getString("id"),b.getString("taskId"),b.getLong("start"),b.getLong("end"),b.getBoolean("locked"),b.getBoolean("done"),owner)},
    rows("checkins"){c->CheckInRow(c.getString("id"),c.getString("taskId"),c.getLong("completedAt"),c.getInt("rating"),owner)},
    rows("work_times"){w->DailyWorkTimeRow(w.getString("date"),w.getInt("startMinute"),w.getInt("endMinute"),owner)},
    p.getJSONObject("settings").let{SettingRow(startHour=it.getInt("startHour"),endHour=it.getInt("endHour"),ownerId=owner)},
    rows("reminders"){r->TaskReminderRow(owner,r.getString("taskId"),r.getBoolean("enabled"),r.getString("method"),r.getString("timing"))},
   ).also{it.validate()}
  }
  suspend fun apply(repo:DeskRepository,owner:String,expected:String,next:ScheduleBackup,current:()->Boolean)=repo.transaction{
   check(current()){ "账户或 VIP 已变化" }
   check(repo.dao.unresolvedSyncBatches(owner)==0){"存在未完成的旧同步请求，请先处理后再导入"}
   check(capture(repo,owner).json()==expected){"本地日程已变化，请重新预览"}
   next.validate()
   repo.dao.clearReminders(owner);repo.dao.clearBlocks(owner);repo.dao.clearCheckIns(owner);repo.dao.clearWorkTimes(owner);repo.dao.clearTasks(owner)
   next.tasks.forEach{repo.dao.save(it.copy(ownerId=owner))};next.blocks.forEach{repo.dao.save(it.copy(ownerId=owner))}
   next.checks.forEach{repo.dao.save(it.copy(ownerId=owner))};next.workTimes.forEach{repo.dao.save(it.copy(ownerId=owner))}
   repo.dao.save(next.settings.copy(ownerId=owner));next.reminders.forEach{repo.dao.save(it.copy(ownerId=owner))}
   check(current()){ "账户已变化" }
  }
 }
}
