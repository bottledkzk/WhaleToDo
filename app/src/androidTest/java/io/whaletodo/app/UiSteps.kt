package io.whaletodo.app

import androidx.test.uiautomator.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class UiSteps(val device: UiDevice) {
    fun text(s: String): UiObject2 =
        device.wait(Until.findObject(By.text(s)), 10000) ?: error("Missing control: $s")

    fun click(s: String) {
        text(s)
        device.findObjects(By.text(s)).last().click()
        device.waitForIdle()
    }

    fun desc(s: String): UiObject2 =
        device.wait(Until.findObject(By.desc(s)), 5000) ?: error("Missing control: $s")

    private fun dialClick(description: String, last: Boolean = false) {
        desc(description)
        val items = device.findObjects(By.desc(description))
        val bounds = (if (last) items.last() else items.first()).visibleBounds
        device.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
        // Material's clock hand and hour/minute transitions animate before the next gesture.
        android.os.SystemClock.sleep(800)
        device.waitForIdle()
    }

    fun time(hour: Int, minute: Int) {
        dialClick("Select hour")
        check(device.wait(Until.hasObject(By.desc("23 hours").clazz("android.view.View")), 5000)) {
            "Hour face not ready"
        }
        check(device.wait(Until.gone(By.desc("55 hours").clazz("android.view.View")), 5000)) {
            "Old minute face still present"
        }
        dialClick("$hour hours", last = true)
        dialClick("Select minutes")
        check(
            device.wait(Until.hasObject(By.desc("55 minutes").clazz("android.view.View")), 5000)
        ) {
            "Minute face not ready"
        }
        check(device.wait(Until.gone(By.desc("23 minutes").clazz("android.view.View")), 5000)) {
            "Old hour face still present"
        }
        val dialMinute = minute / 5 * 5
        dialClick("$dialMinute minutes", last = true)
        repeat(minute - dialMinute) {
            desc("增加一分钟").click()
            device.waitForIdle()
        }
        var actual = emptyList<String>()
        repeat(6) {
            actual =
                device.findObjects(By.clazz("android.widget.TextView")).map {
                    "${it.text}:${it.contentDescription}"
                }
            val hourText = "%02d:$hour hours".format(hour)
            val minuteText = "%02d:$minute minutes".format(minute)
            if (hourText in actual && minuteText in actual) return
            android.os.SystemClock.sleep(500)
        }
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-selection-failure.png")
        error("Requested $hour:$minute; selected $actual")
    }

    fun deadline(date: LocalDate, hour: Int = 23, minute: Int = 55) {
        desc("选择截止时间").click()
        text("截止日期")
        var month = YearMonth.from(ZonedDateTime.now().plusHours(1))
        while (month < YearMonth.from(date)) {
            desc("Change to next month").click()
            device.waitForIdle()
            month = month.plusMonths(1)
        }
        click(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US)))
        click("下一步")
        time(hour, minute)
        click("确定")
    }
}
