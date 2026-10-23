package io.whaletodo.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.whaletodo.app.data.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val ui = UiSteps(device)

    private fun start() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        device.waitForIdle()
        ui.text("WorkTime")
    }

    private fun records() = runBlocking {
        val db = DeskDatabase.open(context)
        try {
            db.dao().tasks()
        } finally {
            db.close()
        }
    }

    private fun workTimes() = runBlocking {
        val db = DeskDatabase.open(context)
        try {
            db.dao().workTimes()
        } finally {
            db.close()
        }
    }

    @Test
    fun todayClockKeepsMinutesRejectsEarlierEndAndSupportsCancellation() {
        start()
        ui.click("WorkTime")
        ui.text("今日 WorkTime · 开始")
        ui.time(9, 15)
        ui.click("下一步")
        ui.text("今日 WorkTime · 结束")
        ui.time(8, 0)
        ui.text("结束时间不能早于开始时间")
        assertFalse(ui.text("保存").parent.isEnabled)
        // Exercise the offered previous-step action before choosing a valid end time.
        ui.click("上一步")
        ui.text("今日 WorkTime · 开始")
        ui.click("下一步")
        ui.time(18, 30)
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-worktime-test.png")
        ui.click("保存")
        ui.text("09:15–18:30")
        assertEquals(
            DailyWorkTimeRow(LocalDate.now().toString(), 555, 1110),
            workTimes().find { it.date == LocalDate.now().toString() },
        )
        ui.click("WorkTime")
        ui.time(10, 0)
        ui.click("取消")
        assertEquals(555, workTimes().first { it.date == LocalDate.now().toString() }.startMinute)
    }

    @Test
    fun detailsCarryTitleCancelWithoutSavingThenCreateOnceWithDeadline() {
        start()
        val before = records().size
        val title = "详细记录 ${System.currentTimeMillis()%10000}"
        ui.click("＋ 快速记下")
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)!!.text = title
        ui.click("详细")
        assertEquals(title, device.findObjects(By.clazz("android.widget.EditText")).first().text)
        ui.click("取消")
        ui.click("取消")
        assertEquals(before, records().size)
        ui.click("＋ 快速记下")
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)!!.text = title
        ui.click("详细")
        val date = LocalDate.now().plusDays(2)
        ui.deadline(date, 23, 55)
        device.findObjects(By.clazz("android.widget.EditText"))[1].text = "35"
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-detail-test.png")
        ui.click("记下")
        device.waitForIdle()
        ui.click("待办")
        ui.text(title)
        val saved = records().filter { it.title == title }
        assertEquals(1, saved.size)
        assertEquals(before + 1, records().size)
        assertEquals(35, saved.single().minutes)
        assertEquals(
            date.atTime(23, 55).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            saved.single().deadline,
        )
    }

    @Test
    fun clockAllowsExactLastMinuteAndCancelsWithoutSaving() {
        start()
        val before = workTimes()
        ui.click("WorkTime")
        ui.time(23, 59)
        ui.click("下一步")
        ui.text("今日 WorkTime · 结束")
        assertNotNull(ui.desc("23 hours"))
        assertNotNull(ui.desc("59 minutes"))
        assertTrue(ui.text("保存").parent.isEnabled)
        ui.click("取消")
        assertEquals(before, workTimes())
        ui.click("WorkTime")
        ui.time(0, 0)
        ui.click("下一步")
        ui.time(23, 59)
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-2359-test.png")
        ui.click("取消")
        assertEquals(before, workTimes())
    }
}
