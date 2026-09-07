package dev.wasil.permit.parking.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.wasil.permit.parking.zones.Reminder
import org.junit.Test

/**
 * Posts the two v0.8.0 reminders for real, so they can be looked at.
 *
 * Not an assertion, and not pretending to be one. This project's most expensive
 * defects — black-on-black text, an invisible slider, a uniform tariff tint —
 * were every one of them invisible to a passing test suite and obvious in a
 * screenshot, which is why `CLAUDE.md` opens with *look at the screen before
 * claiming something works*. Notification copy is exactly that kind of thing:
 * the unit tests prove the strings are what I meant, and only the shade can show
 * whether the title fits on one line on a phone.
 *
 * Two methods rather than one because both reminders share
 * [ParkNotifications.REMINDER_ID] on purpose, so posting them together would
 * only ever show the second. Run them separately:
 *
 * ```
 * adb shell am instrument -w -e class \
 *   'dev.wasil.permit.parking.android.ReminderNotificationScreenshotTest#startsOwing' \
 *   dev.wasil.permit.test/androidx.test.runner.AndroidJUnitRunner
 * adb shell cmd statusbar expand-notifications
 * ```
 */
class ReminderNotificationScreenshotTest {

    private fun notifications(): ParkNotifications {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ParkNotifications.createChannels(context)
        return ParkNotifications(context)
    }

    /** Monday 08:30 in a T16B street: €3,01/h from 09:00, nothing covering it. */
    @Test
    fun startsOwing() {
        notifications().reminder(
            reminder = Reminder.StartsOwing(inMin = 30, rateText = "€3,01/h"),
            dayOfWeek = 0,
            minuteOfDay = 510,
            place = "Molenwijk · Computerweg",
        )
        Thread.sleep(1_200)
    }

    /** The same street at 18:50, ten minutes short of free. */
    @Test
    fun becomesFree() {
        notifications().reminder(
            reminder = Reminder.BecomesFree(inMin = 10),
            dayOfWeek = 0,
            minuteOfDay = 1130,
            place = "Molenwijk · Computerweg",
        )
        Thread.sleep(1_200)
    }

    /**
     * Both cards at once — the regression found auditing v0.8.0 before its tag.
     *
     * The reminder first borrowed the permit card's `ACTION_IGNORE`, so tapping
     * "Ignore" on the clock reminder ran the shared handler, cancelled the
     * permit question (`EVENT_ID`) and erased its persisted `PendingDecision`.
     * Posts the park question and a reminder together; tap Ignore on the
     * reminder and the park question must still be standing.
     */
    @Test
    fun bothCardsUp() {
        val n = notifications()
        n.askManualDecision()
        Thread.sleep(600)
        n.reminder(
            reminder = Reminder.StartsOwing(inMin = 30, rateText = "€3,01/h"),
            dayOfWeek = 0,
            minuteOfDay = 510,
            place = "Molenwijk · Computerweg",
        )
        Thread.sleep(1_200)
    }

    /**
     * The worst case for the layout: a long place name, a stepped rate, and a
     * boundary on the far side of midnight so the title carries a day prefix
     * too. If anything is going to be clipped it is this.
     */
    @Test
    fun startsOwingLongest() {
        notifications().reminder(
            reminder = Reminder.StartsOwing(inMin = 20, rateText = "from €1,72/h"),
            dayOfWeek = 6,
            minuteOfDay = 1430,
            place = "Oostelijk Havengebied · Cruquiusweg",
        )
        Thread.sleep(1_200)
    }
}
