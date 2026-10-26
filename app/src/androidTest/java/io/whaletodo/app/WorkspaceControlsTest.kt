package io.whaletodo.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.whaletodo.app.data.*
import io.whaletodo.core.Window
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceControlsTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val device = UiDevice.getInstance(instrument)
    private val ui = UiSteps(device)
    private fun start() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.waitForIdle(); ui.text("WorkTime")
    }
    private fun <T> database(action: suspend (DeskDatabase) -> T): T = runBlocking {
        val db = DeskDatabase.open(context)
        try { action(db) } finally { db.close() }
    }
    private fun seed(title: String, deadline: Long = 0): TaskRow = TaskRow("ui-${System.nanoTime()}", title, deadline = deadline).also { t -> database { it.dao().save(t) } }
    private fun ensureDesc(label: String): UiObject2 {
        repeat(5) {
            device.findObject(By.desc(label))?.let { found -> if (found.visibleBounds.height() > 20) return found }
            device.swipe(530, 1600, 530, 950, 25); device.waitForIdle()
        }
        return ui.desc(label)
    }
    private fun choose(label: String, date: LocalDate, hour: Int, minute: Int) {
        ensureDesc(label).click(); ui.text("${label}日期")
        val calendarDate = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US))
        if (device.findObject(By.text(calendarDate)) == null) {
            ui.desc("Change to next month").click(); device.waitForIdle()
        }
        ui.click(calendarDate); ui.click("下一步"); ui.time(hour, minute); ui.click("确定")
    }
    @Test fun manualClockSearchAndMergedSchedulePersist() {
        val title = "手动学习窗口 ${System.currentTimeMillis() % 10000}"
        val task = seed(title)
        start(); ui.click("待办"); ui.desc("搜索事务").click()
        ui.desc("搜索事务输入").text = "学习窗口"
        ui.click(title)
        ensureDesc("手动安排开关").click(); device.waitForIdle()
        val day = LocalDate.now().plusDays(1)
        choose("安排开始", day, 14, 10)
        choose("安排结束", day, 15, 20)
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-manual-editor.png")
        ui.click("保存")
        ui.text(title)
        val block = database { it.dao().blocks().single { row -> row.taskId == task.id } }
        assertTrue(block.locked)
        assertEquals(day.atTime(14, 10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), block.start)
        assertEquals(70L, (block.end - block.start) / 60000)
        // A new activity reads the same interval and the day view includes it.
        start(); ui.click("待办"); ui.click("日程"); ui.desc("后一天").click(); ui.text(title)
        ui.desc("分享 $title").click(); device.waitForIdle()
        ui.text("分享样式"); ui.click("分享文字"); ui.text("登录后继续"); ui.click("返回"); ui.click("关闭")
        ui.click("分享当天"); ui.text("分享样式"); ui.click("关闭")
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agenda.png")
    }
    @Test fun calendarShowsThreeStatesAndCompletionCanBeChecked() {
        val day = LocalDate.now(); val zone = ZoneId.systemDefault()
        val done = seed("预览完成 ${System.nanoTime()}")
        val late = seed("预览逾期 ${System.nanoTime()}", day.atStartOfDay(zone).toInstant().toEpochMilli())
        val future = seed("预览未来 ${System.nanoTime()}", day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli())
        database { DeskRepository(it).complete(done, 4) }
        start(); ui.click("预览"); ui.text("活动预览")
        val selector = By.descContains("$day 完成")
        val cell = device.wait(Until.findObject(selector), 5000) ?: error("Missing calendar date")
        assertTrue(cell.contentDescription.contains("逾期")); assertTrue(cell.contentDescription.contains("待办"))
        cell.click(); device.waitForIdle()
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-calendar.png")
        ui.click("待办"); ui.desc("搜索事务").click(); ui.desc("搜索事务输入").text = "预览未来"
        ui.desc("完成状态 ${future.title}").click(); ui.click("完美"); ui.click("完成打卡")
        assertTrue(database { it.dao().tasks().first { t -> t.id == future.id }.done })
        assertFalse(database { it.dao().tasks().first { t -> t.id == late.id }.done })
    }
    @Test fun assistantAccountThemeAndRealSystemTimezoneRefresh() {
        val original = device.executeShellCommand("getprop persist.sys.timezone").trim()
        start(); ui.click("助手"); ui.text("把想法，变成下一步")
        device.findObject(By.clazz("android.widget.EditText")).text = "请安排明天的学习"
        ui.click("发送"); ui.text("登录后继续"); ui.click("前往账户"); ui.text("注册账户");ui.click("取消")
        ui.text("本机访客"); ui.click("每日签到"); ui.text("登录后领取每日积分"); ui.click("关闭")
        ui.click("设置");ui.click("外观设定");ui.click("深色");ui.click("跟随系统");ui.desc("返回设置").click();ui.click("时间和地区")
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-my-dark.png")
        try {
            device.executeShellCommand("cmd alarm set-timezone Asia/Tokyo")
            ui.text("Asia/Tokyo")
            device.executeShellCommand("cmd alarm set-timezone America/Los_Angeles")
            ui.text("America/Los_Angeles")
        } finally {
            device.executeShellCommand("cmd alarm set-timezone $original")
        }
        ui.desc("返回设置").click();ui.desc("返回我的").click();ui.click("助手")
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-assistant.png")
    }
}
