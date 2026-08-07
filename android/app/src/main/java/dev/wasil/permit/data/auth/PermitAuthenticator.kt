package dev.wasil.permit.data.auth

import dev.wasil.permit.data.api.BrowserHeadersInterceptor
import dev.wasil.permit.data.api.ClientProductLogInterceptor
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitJson
import dev.wasil.permit.data.store.CredentialStore
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/** Synchronous login used only from the Authenticator (which runs off the main thread). */
class BlockingLoginClient(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    fun login(username: String, password: String): String? {
        val body = PermitJson.encodeToString(LoginRequest(username, password))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("ssp/login_check").build())
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            return runCatching {
                PermitJson.decodeFromString<LoginResponse>(text).token
            }.getOrNull()
        }
    }
}

/**
 * The JWT expires after exactly 1 hour with no refresh token. On any 401 we
 * re-login with the stored credentials and replay the original request once.
 * The user must never see "please log in again".
 */
class PermitAuthenticator(
    private val credentialStore: CredentialStore,
    private val tokenStore: TokenStore,
    private val loginClient: BlockingLoginClient,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // A 401 from the login endpoint means bad credentials - retrying loops forever.
        if (response.request.url.encodedPath.endsWith("/ssp/login_check")) return null
        // Only one retry per request.
        if (response.priorResponse != null) return null

        val config = credentialStore.load() ?: return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        val newToken = synchronized(this) {
            val current = tokenStore.token
            // Another request may have refreshed the token while we waited.
            if (current != null && current != failedToken) {
                current
            } else {
                loginClient.login(config.username, config.password)
                    ?.also { tokenStore.token = it }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}

fun buildAuthenticatedClient(
    baseUrl: HttpUrl,
    tokenStore: TokenStore,
    credentialStore: CredentialStore,
    /** Debug builds only — see [ClientProductLogInterceptor] for why it exists. */
    logClientProduct: Boolean = false,
): OkHttpClient {
    val bareClient = OkHttpClient.Builder()
        .addInterceptor(BrowserHeadersInterceptor())
        .build()
    return OkHttpClient.Builder()
        .addInterceptor(BrowserHeadersInterceptor())
        .addInterceptor(AuthInterceptor(tokenStore))
        // A network interceptor rather than an application one, so it sees the
        // body of the retried request after a 401 refresh rather than the empty
        // 401 itself.
        .addNetworkInterceptor(ClientProductLogInterceptor(logClientProduct))
        .authenticator(
            PermitAuthenticator(credentialStore, tokenStore, BlockingLoginClient(baseUrl, bareClient))
        )
        .build()
}
