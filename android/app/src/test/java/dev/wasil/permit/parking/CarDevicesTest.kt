package dev.wasil.permit.parking

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDevicesTest {

    @Test
    fun `a car stereo and a car kit are cars`() {
        assertTrue(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_CAR_AUDIO))
        assertTrue(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_HANDSFREE))
    }

    /** The actual complaint: earbuds sitting next to the car in the list. */
    @Test
    fun `earbuds, headsets and speakers are not`() {
        assertFalse(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_HEADPHONES))
        assertFalse(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertFalse(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_PORTABLE_AUDIO))
        assertFalse(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_LOUDSPEAKER))
        assertFalse(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_MICROPHONE))
    }

    @Test
    fun `a laptop, a phone, a watch and a keyboard are not`() {
        assertFalse(CarDevices.couldBeACar(0x0104)) // COMPUTER / laptop
        assertFalse(CarDevices.couldBeACar(0x020C)) // PHONE / smartphone
        assertFalse(CarDevices.couldBeACar(0x0704)) // WEARABLE / wrist watch
        assertFalse(CarDevices.couldBeACar(0x0540)) // PERIPHERAL / keyboard
    }

    /**
     * The direction the filter is allowed to be wrong in.
     *
     * A head unit that declares nothing useful still has to appear, because a
     * filter that hides the right device leaves someone unable to pair their car
     * with no explanation on screen. Both vague codes stay in, and so does a
     * class that could not be read at all.
     */
    @Test
    fun `anything vague stays in the list rather than being guessed away`() {
        assertTrue(CarDevices.couldBeACar(CarDevices.AUDIO_VIDEO_UNCATEGORIZED))
        assertTrue(CarDevices.couldBeACar(CarDevices.MAJOR_UNCATEGORIZED))
        assertTrue(
            "'we could not ask' must never render as 'not your car'",
            CarDevices.couldBeACar(CarDevices.UNKNOWN_DEVICE_CLASS),
        )
    }

    /**
     * The copied constants against the real ones.
     *
     * [CarDevices] restates `BluetoothClass.Device`'s codes so the rule can be
     * tested on the JVM, the same way `PlayServicesSignals` restates
     * `DetectedActivity`'s. This is what stops the copy drifting from the
     * original in silence — the failure mode would be a filter that quietly
     * classifies everything wrongly.
     */
    @Test
    fun `the restated bluetooth codes are the real ones`() {
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, CarDevices.AUDIO_VIDEO_CAR_AUDIO)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE, CarDevices.AUDIO_VIDEO_HANDSFREE)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, CarDevices.AUDIO_VIDEO_HEADPHONES)
        assertEquals(
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            CarDevices.AUDIO_VIDEO_WEARABLE_HEADSET,
        )
        assertEquals(
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            CarDevices.AUDIO_VIDEO_PORTABLE_AUDIO,
        )
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, CarDevices.AUDIO_VIDEO_LOUDSPEAKER)
        assertEquals(BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE, CarDevices.AUDIO_VIDEO_MICROPHONE)
        assertEquals(
            BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED,
            CarDevices.AUDIO_VIDEO_UNCATEGORIZED,
        )
        assertEquals(BluetoothClass.Device.Major.AUDIO_VIDEO, CarDevices.MAJOR_AUDIO_VIDEO)
        assertEquals(BluetoothClass.Device.Major.UNCATEGORIZED, CarDevices.MAJOR_UNCATEGORIZED)
    }

    @Test
    fun `the escape hatch counts what it is offering, and disappears when there is nothing`() {
        assertNull(otherDevicesLabel(0))
        assertEquals("Show 1 other paired device", otherDevicesLabel(1))
        assertEquals("Show 6 other paired devices", otherDevicesLabel(6))
    }
}
