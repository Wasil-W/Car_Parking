package dev.wasil.permit

import android.app.Application
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.buildPermitApi
import dev.wasil.permit.data.auth.TokenStore
import dev.wasil.permit.data.auth.buildAuthenticatedClient
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.EncryptedCredentialStore
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Composition root - two users, one screen: no DI framework needed. */
class PermitApp : Application() {
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var repository: PermitRepository
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = EncryptedCredentialStore(this)
        val baseUrl = ApiConstants.BASE_URL.toHttpUrl()
        val client = buildAuthenticatedClient(baseUrl, TokenStore(), credentialStore)
        repository = PermitRepository(buildPermitApi(baseUrl, client))
    }
}
