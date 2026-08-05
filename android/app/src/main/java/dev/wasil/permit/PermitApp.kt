package dev.wasil.permit

import android.app.Application
import android.content.Context
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.buildPermitApi
import dev.wasil.permit.data.auth.TokenStore
import dev.wasil.permit.data.auth.buildAuthenticatedClient
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.EncryptedCredentialStore
import dev.wasil.permit.parking.ClaimPermit
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.GuardedClaim
import dev.wasil.permit.parking.ParkLogStore
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkLogStore
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.android.ParkNotifications
import dev.wasil.permit.parking.key
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.RtdbSharedStateStore
import dev.wasil.permit.parking.shared.SharedStateStore
import dev.wasil.permit.parking.shared.UnconfiguredSharedStateStore
import dev.wasil.permit.parking.shared.roomIdFor
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffAreas
import dev.wasil.permit.parking.zones.ZoneResolver
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Composition root - two users, one screen: no DI framework needed. */
class PermitApp : Application() {
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var repository: PermitRepository
        private set
    lateinit var parkStateStore: ParkStateStore
        private set
    lateinit var freeZoneStore: FreeZoneStore
        private set
    lateinit var parkLogStore: ParkLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = EncryptedCredentialStore(this)
        val baseUrl = ApiConstants.BASE_URL.toHttpUrl()
        val client = buildAuthenticatedClient(baseUrl, TokenStore(), credentialStore)
        repository = PermitRepository(buildPermitApi(baseUrl, client))
        parkStateStore = PrefsParkStateStore.from(this)
        val prefs = getSharedPreferences("park_state", Context.MODE_PRIVATE)
        freeZoneStore = PrefsFreeZoneStore(prefs)
        parkLogStore = PrefsParkLogStore(prefs)
        ParkNotifications.createChannels(this)
    }

    /** Plain client for RTDB — the permit client's auth/headers must not leak there. */
    private val plainHttp by lazy { OkHttpClient() }

    /** Same reasoning as [plainHttp]: nothing of the permit session goes to a
     * public routing service. */
    val routeClient: OkHttpClient by lazy { OkHttpClient() }

    /** Bundled Amsterdam tariff areas; null when the asset is missing/corrupt. */
    val tariffAreas: List<TariffArea>? by lazy {
        runCatching {
            assets.open("amsterdam_tarieven.json").bufferedReader().use { it.readText() }
        }.mapCatching { json -> TariffAreas.parse(json).takeIf { it.isNotEmpty() } }
            .getOrNull()
    }

    /** Rebuilt per call: settings (URL, my car, credentials) can change at runtime. */
    fun sharedStateStore(): SharedStateStore {
        val url = parkStateStore.syncUrl?.toHttpUrlOrNull() ?: return UnconfiguredSharedStateStore
        val username = credentialStore.load()?.username ?: return UnconfiguredSharedStateStore
        val me = parkStateStore.myCar ?: return UnconfiguredSharedStateStore
        return RtdbSharedStateStore(
            baseUrl = url,
            room = roomIdFor(username),
            me = me.key(),
            other = me.other().key(),
            client = plainHttp,
        )
    }

    fun guardedClaim(): GuardedClaim {
        val notifications = ParkNotifications(this)
        return GuardedClaim(
            repository, credentialStore, parkStateStore, sharedStateStore(),
            ClaimPermit(repository, credentialStore, parkStateStore, notifications, parkLogStore),
        )
    }

    fun zoneResolver(): ZoneResolver =
        ZoneResolver(parkStateStore.homeZone, freeZoneStore.all(), tariffAreas)
}
