package dev.wasil.permit.parking.android

import dev.wasil.permit.R
import dev.wasil.permit.parking.MyCar
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIconsTest {

    @Test fun `wasil holding gets the left-dot icon`() {
        assertEquals(R.drawable.ic_notification_wasil, notificationIconFor(MyCar.WASIL))
    }

    @Test fun `walid holding gets the right-dot icon`() {
        assertEquals(R.drawable.ic_notification_walid, notificationIconFor(MyCar.WALID))
    }

    @Test fun `no known holder gets the centred neutral icon`() {
        assertEquals(R.drawable.ic_notification, notificationIconFor(null))
    }
}
