package dev.wasil.permit.parking.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult

class ActivityUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val top = result.mostProbableActivity
        PlayServicesSignals.append(context, top.type, top.confidence, result.time)
    }
}
