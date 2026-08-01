package dev.wasil.permit.parking.route

import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val ROUTE_TIMEOUT_MS = 6_000L

/**
 * The walking route, or a straight line when one cannot be had.
 *
 * Never null and never throws: "Walk to car" must always draw something. A
 * straight line is honest about what it is — [WalkRoute.durationS] stays zero
 * so the summary shows a distance and no invented walking time.
 */
suspend fun fetchWalkRoute(client: OkHttpClient, from: GeoPoint, to: GeoPoint): WalkRoute =
    withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(OsrmRoute.url(from, to)).build()
                client.newCall(request).execute().use { response ->
                    response.body?.string()?.let(OsrmRoute::parse)
                }
            }.getOrNull()
        }
    } ?: straightLine(from, to)

private fun straightLine(from: GeoPoint, to: GeoPoint) = WalkRoute(
    points = listOf(from, to),
    distanceM = distanceMeters(from, to),
    durationS = 0.0,
)
