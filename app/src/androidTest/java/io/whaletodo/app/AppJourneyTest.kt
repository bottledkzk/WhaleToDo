package io.whaletodo.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @Test
    fun captureArrangeCompleteAndRelaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val title = "整理软件工程需求 ${System.currentTimeMillis()%10000}"
        fun text(s: String) =
            device.wait(Until.findObject(By.text(s)), 10000) ?: error("Missing control: $s")
        fun click(s: String) {
            text(s)
            device.findObjects(By.text(s)).last().click()
            device.waitForIdle()
        }
        fun start() {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            device.waitForIdle()
            text("WhaleToDo")
        }
        start()
        click("＋ 快速记下")
        val capture =
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
                ?: error("No title input")
        capture.text = title
        click("记下")
        text(title)
        click(title)
        UiSteps(device).deadline(LocalDate.now().plusDays(2))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue(fields.size >= 2)
        fields[1].text = "90"
        click("保存")
        click("自动安排")
        text("确认新计划")
        click("采用计划")
        click("计划")
        text(title)
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-plan.png")
        click("待办")
        val check =
            device.wait(Until.findObject(By.desc("完成状态 $title")), 5000)
                ?: error("Missing completion control")
        check.click()
        device.waitForIdle()
        click("不错")
        click("完成打卡")
        device.pressHome()
        start()
        click("待办")
        // Include completed records after recreating the activity.
        val toggle =
            device.wait(Until.findObject(By.desc("显示已完成事项")), 5000)
                ?: error("Missing completed filter")
        toggle.click()
        device.waitForIdle()
        assertNotNull(device.wait(Until.findObject(By.text(title)), 5000))
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-persisted.png")
    }
}
