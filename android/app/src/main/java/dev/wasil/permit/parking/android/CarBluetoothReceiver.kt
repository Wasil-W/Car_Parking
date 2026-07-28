package dev.wasil.permit.parking.android

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.work.WorkManager
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
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ParkWorkers.enqueueDetection(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Back in the car: driving again, everything resets.
                WorkManager.getInstance(context).cancelUniqueWork(ParkWorkers.DETECTION_WORK)
                store.parked = false
                ParkNotifications.dismissEvents(context)
            }
        }
    }
}
