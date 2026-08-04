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
                // The parked pin is now wrong, not merely old — the car is
                // wherever you are. Leaving it behind made the map point at
                // the previous parking spot for the whole drive. It only has
                // meaning once you have walked away from the car again.
                store.lastParkLocation = null
                SharedSync.requestSync(context)
                ParkNotifications.dismissEvents(context)
            }
        }
    }
}
