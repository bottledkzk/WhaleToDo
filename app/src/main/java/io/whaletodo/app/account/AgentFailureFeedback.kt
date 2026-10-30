package io.whaletodo.app.account

import io.whaletodo.network.AiRequestView

/** Only bounded domain validation feedback may cross into the UI; pending/provider errors stay generic. */
internal fun modelFailureMessage(view: AiRequestView): String? {
    if(view.status!="failed"||view.errorCode!="MODEL_OUTPUT_INVALID")return null
    val text=view.text?.trim()?:return null
    if(text.length !in 1..200||text.any{it.isISOControl()})return null
    return text.takeIf { value -> listOf("请明确","本次要求","存在同名","未找到","无法唯一","请指定","日期无效","该本地时间",
        "日程资料","事项已有","所选时段","新增安排","提案","固定、","调整后的","请保留","同一安排","事项不存在",
        "日记草稿","配文未","本次提案").any{value.startsWith(it)} }
}
