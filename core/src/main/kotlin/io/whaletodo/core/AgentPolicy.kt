package io.whaletodo.core

import java.time.Instant
import java.util.Locale

/** Stable wire codes; labels and colors are presentation, never account permissions. */
enum class TaskCategory(val code: String, val label: String, val symbol: String, val color: Long) {
    NONE("none", "无", "○", 0xFF6F7882), STUDY("study", "学习", "▤", 0xFF5579B8),
    EXERCISE("exercise", "锻炼", "↗", 0xFFE38A45), ENTERTAINMENT("entertainment", "娱乐", "♫", 0xFFB170AB),
    WORK("work", "工作", "▣", 0xFF477F87), SOCIAL("social", "交际", "◎", 0xFFC56C79),
    TRAVEL("travel", "旅游", "⌁", 0xFF479883), REST("rest", "休息", "☾", 0xFF8B80B5);
    companion object { fun from(code: String) = entries.firstOrNull { it.code == code } ?: NONE }
}

enum class AgentMode(val code: String, val label: String, val hint: String, val edits: Boolean = false) {
    CHAT("chat", "聊天", "说说你的想法…"), PLAN("plan", "计划建议", "想怎样安排？"),
    REVIEW("review", "周期复盘", "回顾哪段时间？"), DIARY("diary", "日记", "今天有什么值得记下？"),
    SHARE_COPY("share_copy", "分享配文", "想分享什么？"), EDIT("edit", "调整日程", "想调整哪些安排？", true)
}
data class AgentModel(val code: String, val title: String, val canEdit: Boolean = false, val requiresVip: Boolean = canEdit)
data class AgentAccess(val userId: String? = null, val vip: Boolean = false, val availablePoints: Int = 0,
    val connected: Boolean = false, val editModels: Set<String> = emptySet())
enum class GateReason { LOGIN, VIP, POINTS, CONSENT, MODEL, CONNECTION }
object AgentPolicy {
    fun gate(access: AgentAccess, mode: AgentMode, model: AgentModel, consent: Boolean): GateReason? = when {
        access.userId.isNullOrBlank() -> GateReason.LOGIN
        (mode.edits || model.requiresVip) && !access.vip -> GateReason.VIP
        mode.edits && (!model.canEdit || model.code !in access.editModels) -> GateReason.MODEL
        mode.edits && !consent -> GateReason.CONSENT
        access.availablePoints <= 0 -> GateReason.POINTS
        !access.connected -> GateReason.CONNECTION
        else -> null
    }
    fun shareGate(access: AgentAccess, premium: Boolean, trialGranted: Boolean = false): GateReason? = when {
        access.userId.isNullOrBlank() -> GateReason.LOGIN
        premium && !access.vip && !trialGranted -> GateReason.VIP
        else -> null
    }
}
object TokenDisplay {
    fun k(tokens: Int): String = String.format(Locale.ROOT, "%.2fk", tokens.coerceAtLeast(0) / 1000.0)
    fun elapsed(seconds: Long): String = "Working… (%02dm%02ds)".format(Locale.ROOT, seconds.coerceAtLeast(0)/60, seconds.coerceAtLeast(0)%60)
    /** Estimate only; supplier usage remains the settlement authority. */
    fun estimate(text: String): Int = text.codePoints().toArray().sumOf { if(it < 128) 0.3 else 1.2 }.let { kotlin.math.ceil(it).toInt() }
}

sealed interface AgentChange {
    data class RenameTask(val taskId: String, val title: String) : AgentChange
    data class CategorizeTask(val taskId: String, val category: String) : AgentChange
    data class MoveBlock(val blockId: String, val start: Long, val end: Long) : AgentChange
}
data class AgentTaskFact(val id: String, val done: Boolean, val deadline: Long)
data class AgentBlockFact(val id: String, val taskId: String, val start: Long, val end: Long, val locked: Boolean, val done: Boolean)
object AgentChanges {
    fun validate(changes: List<AgentChange>, tasks: List<AgentTaskFact>, blocks: List<AgentBlockFact>,
        now: Long, windows: List<Window>) {
        require(changes.size in 1..12) { "提案需包含1至12项变更" }
        val keys = changes.map { when(it) {
            is AgentChange.RenameTask -> "title:${it.taskId}"
            is AgentChange.CategorizeTask -> "category:${it.taskId}"
            is AgentChange.MoveBlock -> "block:${it.blockId}"
        } }
        require(keys.distinct().size == keys.size) { "提案包含重复变更" }
        changes.forEach { c -> when(c) {
            is AgentChange.RenameTask -> {
                require(tasks.any { it.id == c.taskId && !it.done }) { "事项不存在或已完成" }
                require(c.title.isNotBlank() && c.title.length <= 120) { "标题无效" }
            }
            is AgentChange.CategorizeTask -> {
                require(tasks.any { it.id == c.taskId && !it.done }) { "事项不存在或已完成" }
                require(TaskCategory.entries.any { it.code == c.category }) { "分类无效" }
            }
            is AgentChange.MoveBlock -> {
                val b = blocks.singleOrNull { it.id == c.blockId } ?: error("安排不存在")
                require(!b.locked && !b.done && b.start > now) { "固定、已开始或已完成的安排不能修改" }
                val task = tasks.singleOrNull { it.id == b.taskId } ?: error("事项不存在")
                require(!task.done && c.start > now && c.end - c.start == b.end - b.start && c.end > c.start) { "请保留工时并选择未来时段" }
                require(task.deadline == 0L || c.end <= task.deadline) { "安排超出截止时间" }
                require(windows.any { c.start >= it.start.toEpochMilli() && c.end <= it.end.toEpochMilli() }) { "安排超出可用时段" }
            }
        } }
        val moved = changes.filterIsInstance<AgentChange.MoveBlock>().associateBy { it.blockId }
        val finalBlocks = blocks.map { b -> moved[b.id]?.let { b.copy(start=it.start, end=it.end) } ?: b }
        finalBlocks.forEachIndexed { i, a -> if (a.id in moved) {
            require(finalBlocks.withIndex().none { (j,b) -> i != j && a.start < b.end && a.end > b.start }) { "提案与其他安排重叠" }
        } }
    }
}
