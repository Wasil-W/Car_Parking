package dev.wasil.permit.parking.android

import dev.wasil.permit.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIconsTest {

    @Test fun `roster slot 0 gets the left-dot icon`() {
        assertEquals(R.drawable.ic_notification_wasil, notificationIconFor(0))
    }

    @Test fun `roster slot 1 gets the right-dot icon`() {
        assertEquals(R.drawable.ic_notification_walid, notificationIconFor(1))
    }

    @Test fun `no known holder gets the centred neutral icon`() {
        assertEquals(R.drawable.ic_notification, notificationIconFor(null))
    }

    /**
     * Past two cars there is no side for the dot to sit on, and inventing one
     * would point the icon at a car chosen by nothing. The neutral drawing is
     * the same one "nobody is holding it" already produces.
     */
    @Test fun `a third car gets the neutral icon rather than a borrowed side`() {
        assertEquals(R.drawable.ic_notification, notificationIconFor(2))
    }
}
