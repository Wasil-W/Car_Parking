package dev.wasil.permit.parking

/**
 * Which paired Bluetooth devices could plausibly be a car.
 *
 * **The complaint.** Settings lists every paired device, so Wasil's earbuds sit
 * in the same list as his car, and picking the wrong one breaks detection
 * silently — the app then waits for a stereo that never disconnects at a parking
 * spot. Reported 2026-08-08.
 *
 * **The rule, and which way it errs.** Bluetooth's Class of Device carries a
 * major class and a minor one, and the minor class is unusually informative
 * here: car kits declare themselves. So the filter keeps anything audio that is
 * not *specifically* something worn or carried, and drops everything that is not
 * audio at all. A pair of earbuds says HEADPHONES; a watch says WEARABLE; a
 * laptop says COMPUTER. None of them can be the car.
 *
 * It errs inclusive on purpose. **A filter that hides the right device is worse
 * than a long list** — the user would be left with no way to pair their car and
 * no explanation — so the two vague codes, uncategorised audio and uncategorised
 * everything, stay in. Aftermarket head units are exactly the population that
 * ships a lazy Class of Device, and they are also the population least likely to
 * be guessed at correctly from a name. On top of that the list keeps a way to
 * show everything, which is the real safety net; this is a sort, not a gate.
 *
 * Pure over an `Int` so it can be tested on the JVM. The constants mirror
 * `android.bluetooth.BluetoothClass.Device`, restated here rather than imported
 * for the same reason `PlayServicesSignals` restates `DetectedActivity`'s codes
 * — and pinned against the real values by a test, so the copy cannot drift.
 */
object CarDevices {
    /** Bits 8–12 of the Class of Device: what kind of thing it is at all. */
    const val MAJOR_MASK = 0x1F00

    const val MAJOR_AUDIO_VIDEO = 0x0400
    /** "Something, but the maker did not say what." Kept — see the class note. */
    const val MAJOR_UNCATEGORIZED = 0x1F00

    const val AUDIO_VIDEO_UNCATEGORIZED = 0x0400
    const val AUDIO_VIDEO_WEARABLE_HEADSET = 0x0404
    const val AUDIO_VIDEO_HANDSFREE = 0x0408
    const val AUDIO_VIDEO_MICROPHONE = 0x0410
    const val AUDIO_VIDEO_LOUDSPEAKER = 0x0414
    const val AUDIO_VIDEO_HEADPHONES = 0x0418
    const val AUDIO_VIDEO_PORTABLE_AUDIO = 0x041C
    const val AUDIO_VIDEO_CAR_AUDIO = 0x0420

    /**
     * The audio devices that are definitely not a car: worn, held, or plugged in
     * at a desk. Everything else audio is left in.
     *
     * Named as an exclusion list rather than as an inclusion list of car codes,
     * because the two behave differently when an unfamiliar code turns up. An
     * inclusion list drops it; this keeps it, which is the direction that cannot
     * strand somebody.
     */
    private val NOT_A_CAR = setOf(
        AUDIO_VIDEO_WEARABLE_HEADSET,
        AUDIO_VIDEO_MICROPHONE,
        AUDIO_VIDEO_LOUDSPEAKER,
        AUDIO_VIDEO_HEADPHONES,
        AUDIO_VIDEO_PORTABLE_AUDIO,
    )

    /**
     * @param deviceClass what `BluetoothClass.getDeviceClass()` returns — major
     *   and minor together — or [UNKNOWN_DEVICE_CLASS] when it could not be read.
     */
    fun couldBeACar(deviceClass: Int): Boolean {
        // A device that will not say what it is stays in the list. Reading the
        // class needs BLUETOOTH_CONNECT and can simply fail, and "we could not
        // ask" must never render as "not your car".
        if (deviceClass == UNKNOWN_DEVICE_CLASS) return true
        return when (deviceClass and MAJOR_MASK) {
            MAJOR_AUDIO_VIDEO -> deviceClass !in NOT_A_CAR
            MAJOR_UNCATEGORIZED -> true
            else -> false
        }
    }

    /** What `BluetoothClass.getDeviceClass()` returns when it has nothing to say. */
    const val UNKNOWN_DEVICE_CLASS = -1
}

/**
 * The row that offers the rest of the list, or null when there is no rest.
 *
 * Counted rather than phrased as a bare "Show all", so the offer is answerable
 * without taking it: seeing "Show 6 other paired devices" tells you the car is
 * probably not among them, and seeing "Show 1" tells you it might be.
 */
fun otherDevicesLabel(hiddenCount: Int): String? = when {
    hiddenCount <= 0 -> null
    hiddenCount == 1 -> "Show 1 other paired device"
    else -> "Show $hiddenCount other paired devices"
}
