package dev.wasil.permit.parking.android

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dev.wasil.permit.parking.PrefsParkStateStore

class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
        ) ?: return
        val store = PrefsParkStateStore.from(context)
        val carMac = store.carMac ?: return
        // Reading the address needs BLUETOOTH_CONNECT on Android 12+.
        val address = runCatching { device.address }.getOrNull() ?: return
        if (!address.equals(carMac, ignoreCase = true)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Order matters, and it is the whole safety story. The link is
                // marked down BEFORE detection is enqueued, because that flag
                // is what unlocks the drive's last position for the claim path
                // — sealAtDisconnect returns null while it is still true. And
                // sampling is stopped before anything else runs, so no poll can
                // still be in flight writing a position after the car stopped.
                store.carLinkConnected = false
                ParkWorkers.stopLiveLocation(context)
                ParkWorkers.enqueueDetection(context)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Back in the car: driving again, everything pending is stale.
                SharedSync.cancelClaimChain(context)
                store.parked = false
                store.parkedOutside = false
                // Driving is a fact, not a gap: this is exactly the case where
                // "not parked outside" is worth publishing.
                store.parkedOutsideKnown = true
                store.lastZoneCode = null
                // The previous drive's trail belongs to the previous drive.
                // Kept, it would be a position from a spot the car has since
                // left, waiting to be sealed as this drive's parking spot.
                store.liveLocation = null
                store.carLinkConnected = true
                ParkWorkers.startLiveLocation(context)
                // The pin is deliberately NOT cleared here any more.
                //
                // It used to be, and the reasoning was sound as far as it went:
                // during a drive the old pin points at a spot the car has left.
                // But deleting it made that the *only* copy, so a park that then
                // failed to produce a position left no car location at all —
                // permanently. Reported twice from real use: "the location
                // disappears and just goes away".
                //
                // The pin is hidden while driving instead, by every screen that
                // draws it, keyed on carLinkConnected. Storage keeps the last
                // known spot, so a failed park falls back to where the car was
                // last seen rather than to nothing. Showing a stale position and
                // saying it is stale beats showing an empty map.
                SharedSync.requestSync(context)
                ParkNotifications.dismissEvents(context)
            }
        }
    }
}
