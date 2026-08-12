package dev.wasil.permit

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
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
import dev.wasil.permit.parking.facilities.FacilityRegistry
import dev.wasil.permit.parking.shared.RtdbSharedStateStore
import dev.wasil.permit.parking.shared.SharedStateStore
import dev.wasil.permit.parking.shared.UnconfiguredSharedStateStore
import dev.wasil.permit.parking.shared.roomIdFor
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffAreas
import dev.wasil.permit.parking.zones.ZoneRegistry
import dev.wasil.permit.parking.zones.ZoneResolver
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Composition root - two cars, one screen: no DI framework needed. */
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

    /**
     * The permit session, held in memory for its one-hour life.
     *
     * A property rather than a value created inline inside [buildAuthenticatedClient],
     * because one screen legitimately needs to end it: "Sign in and find my cars"
     * has to prove the credentials that were just typed, and it cannot do that
     * while a token from the previous sign-in is still attached to every request.
     * See [TokenStore.clear].
     */
    lateinit var tokenStore: TokenStore
        private set

    /**
     * True in a debug build, read from the package's own flag rather than from
     * a generated constant. It gates exactly one thing —
     * [dev.wasil.permit.data.api.ClientProductLogInterceptor] — and that thing
     * writes every plate on the permit account to logcat, so it must be
     * impossible to leave switched on in a release by editing a boolean.
     */
    private val debuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate() {
        super.onCreate()
        credentialStore = EncryptedCredentialStore(this)
        val baseUrl = ApiConstants.BASE_URL.toHttpUrl()
        tokenStore = TokenStore()
        val client = buildAuthenticatedClient(
            baseUrl, tokenStore, credentialStore, logClientProduct = debuggable,
        )
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

    /**
     * Amsterdam's 107 permit zones and 518 neighbourhoods; null when the asset
     * is missing or corrupt. Names only — see [ZoneRegistry]. The claim
     * decision is [zoneResolver]'s and reads the tariff polygons alone.
     */
    val zoneRegistry: ZoneRegistry? by lazy {
        runCatching {
            assets.open("amsterdam_zones.json").bufferedReader().use { it.readText() }
        }.mapCatching { ZoneRegistry.parse(it) }.getOrNull()
    }

    /**
     * Amsterdam's 37 published garages and P+R sites; null when the asset is
     * missing or corrupt, in which case the layer simply never appears.
     *
     * Display only, like [zoneRegistry]. A garage cannot settle the permit —
     * the visitor permit does not pay for one — so nothing on the claim path
     * reads this, and [zoneResolver] is untouched.
     */
    val facilityRegistry: FacilityRegistry? by lazy {
        runCatching {
            assets.open("amsterdam_facilities.json").bufferedReader().use { it.readText() }
        }.mapCatching { FacilityRegistry.parse(it) }.getOrNull()
    }

    /** Rebuilt per call: settings (URL, my car, credentials) can change at runtime. */
    fun sharedStateStore(): SharedStateStore {
        val url = parkStateStore.syncUrl?.toHttpUrlOrNull() ?: return UnconfiguredSharedStateStore
        val config = credentialStore.load() ?: return UnconfiguredSharedStateStore
        val me = config.roster.byId(parkStateStore.thisPhoneDrives)
            ?: return UnconfiguredSharedStateStore
        // Sharing is a two-body arrangement: the room has one node for me and
        // one for the other car. With one car there is nobody to read, and with
        // three the room would need a shape it does not have — so both fall back
        // to the unconfigured store, which returns null and swallows writes,
        // exactly as a blank sync URL already does.
        val other = config.roster.other(me.id) ?: return UnconfiguredSharedStateStore
        return RtdbSharedStateStore(
            baseUrl = url,
            room = roomIdFor(config.username),
            // The same node names as before the roster: ids are the lowercase
            // strings MyCar.key() produced, so an existing pairing keeps its
            // node rather than being silently re-homed.
            me = me.id.value,
            other = other.id.value,
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
        ZoneResolver(
            parkStateStore.homeZone, freeZoneStore.all(), tariffAreas,
            // What gives an area-backed free zone its edges. The registry is the
            // same bundle the map header names places from, so a zone called
            // "Molenwijk" is the Molenwijk the header shows.
            areaShape = { name -> zoneRegistry?.shapeOf(name) },
        )
}
