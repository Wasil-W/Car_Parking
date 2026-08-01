package dev.wasil.permit.parking.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.wasil.permit.parking.ActivitySample
import dev.wasil.permit.parking.ActivityType
import dev.wasil.permit.parking.DetectionSignals
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.cachedFixIfFresh
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real signal source. Activity updates are delivered by Play Services to
 * ActivityUpdatesReceiver, which appends them to a SharedPreferences buffer;
 * this class reads that buffer back. All permission failures degrade to
 * "no signal" (the decision engine then falls back to GPS or Unclear).
 */
class PlayServicesSignals(private val context: Context) : DetectionSignals {

    companion object {
        const val BUFFER_PREFS = "activity_buffer"
        const val KEY_BUFFER = "samples"
        const val KEY_START = "start_ts"

        internal fun append(context: Context, type: Int, confidence: Int, timestampMs: Long) {
            val prefs = context.getSharedPreferences(BUFFER_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_BUFFER, "") ?: ""
            prefs.edit().putString(KEY_BUFFER, "$existing$type:$confidence:$timestampMs;").apply()
        }
    }

    private val prefs = context.getSharedPreferences(BUFFER_PREFS, Context.MODE_PRIVATE)

    private fun updatesIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 100,
        Intent(context, ActivityUpdatesReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        prefs.edit().putString(KEY_BUFFER, "").putLong(KEY_START, System.currentTimeMillis()).apply()
        runCatching {
            ActivityRecognition.getClient(context).requestActivityUpdates(5_000, updatesIntent())
        }
    }

    override suspend fun stop() {
        runCatching {
            ActivityRecognition.getClient(context).removeActivityUpdates(updatesIntent())
        }
    }

    override fun activitySamples(): List<ActivitySample> {
        val start = prefs.getLong(KEY_START, 0L)
        val raw = prefs.getString(KEY_BUFFER, "") ?: ""
        return raw.split(';').filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 3) return@mapNotNull null
            val type = parts[0].toIntOrNull() ?: return@mapNotNull null
            val confidence = parts[1].toIntOrNull() ?: return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            ActivitySample(mapType(type), confidence, ts - start)
        }
    }

    private fun mapType(detectedActivity: Int): ActivityType = when (detectedActivity) {
        0 -> ActivityType.IN_VEHICLE            // DetectedActivity.IN_VEHICLE
        3 -> ActivityType.STILL                 // DetectedActivity.STILL
        2, 7, 8 -> ActivityType.ON_FOOT         // ON_FOOT, WALKING, RUNNING
        else -> ActivityType.OTHER
    }

    /**
     * A fresh fix if one can be had, otherwise the phone's cached one.
     *
     * The fallback is the whole point. `getCurrentLocation` asks the GPS for a
     * new high-accuracy fix and returns null when it cannot get one — which is
     * precisely what happens indoors, and parking at home and walking inside is
     * the single most common thing this app does. Reported from real use on
     * 2026-08-01: the car's pin never appeared, and the app asked about the
     * permit while sitting in the home zone. Both were this one null.
     *
     * The cached fix costs nothing (it is whatever the phone already knew) and
     * at the moment the car's Bluetooth drops it is, in practice, the parking
     * spot itself. [cachedFixIfFresh] is what keeps a much older one out.
     */
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): GeoPoint? = freshFix() ?: cachedFix()

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): GeoPoint? = runCatching {
        suspendCancellableCoroutine<GeoPoint?> { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) })
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private suspend fun cachedFix(): GeoPoint? = runCatching {
        suspendCancellableCoroutine<GeoPoint?> { cont ->
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { loc ->
                    cont.resume(
                        cachedFixIfFresh(
                            loc?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) },
                            ageMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE,
                        ),
                    )
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }.getOrNull()
}
