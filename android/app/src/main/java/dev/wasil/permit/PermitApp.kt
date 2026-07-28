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
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.android.ParkNotifications
import okhttp3.HttpUrl.Companion.toHttpUrl

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

    override fun onCreate() {
        super.onCreate()
        credentialStore = EncryptedCredentialStore(this)
        val baseUrl = ApiConstants.BASE_URL.toHttpUrl()
        val client = buildAuthenticatedClient(baseUrl, TokenStore(), credentialStore)
        repository = PermitRepository(buildPermitApi(baseUrl, client))
        parkStateStore = PrefsParkStateStore.from(this)
        freeZoneStore = PrefsFreeZoneStore(getSharedPreferences("park_state", Context.MODE_PRIVATE))
        ParkNotifications.createChannels(this)
    }
}
