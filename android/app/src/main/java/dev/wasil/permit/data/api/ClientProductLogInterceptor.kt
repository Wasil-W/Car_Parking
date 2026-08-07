package dev.wasil.permit.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Prints the whole `getClientProduct` body, once per call, in debug builds only.
 *
 * It exists because of a question nobody could answer from the code: the app
 * models one field of that response, `vrns`, and the endpoint is called
 * *getClientProduct* — so the permit's own name and type are probably sitting
 * in the same JSON, thrown away by `ignoreUnknownKeys`. Reading what is already
 * arriving beats building anything new, and that is exactly how v0.6.5 found
 * the plates.
 *
 * Nobody can read it from this repo, though: the endpoint needs a live permit
 * login. So this is the instrument rather than the answer. Run a debug build,
 * open Settings → the permit row → "Sign in and find my cars", and:
 *
 * ```
 * adb logcat -s HandoffProduct:V
 * ```
 *
 * Whatever key the product name turns out to sit under goes on
 * [ClientProductResponse]; until then it parses to null and the permit kind is
 * [PermitKind.UNKNOWN], which the app treats as the restricted kind.
 *
 * Debug-only, and it says so twice — once by construction ([enabled] is set
 * from the application's own debuggable flag, never from a build constant that
 * could be flipped) and once here: this body carries every plate on the
 * account, and a release build has no business writing that to a log every
 * other app on the phone could once read.
 */
class ClientProductLogInterceptor(private val enabled: Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!enabled) return response
        if (!chain.request().url.encodedPath.contains(CLIENT_PRODUCT_PATH)) return response

        // peekBody rather than body.string(): reading the real body here would
        // consume the one-shot stream and leave Retrofit with nothing to parse.
        val text = runCatching { response.peekBody(MAX_LOGGED_BYTES).string() }.getOrNull()
            ?: return response
        // Long bodies are chunked, because logcat silently truncates a single
        // line at about 4 kB and a half-printed JSON object answers nothing.
        text.chunked(LOG_CHUNK).forEachIndexed { index, part ->
            Log.d(TAG, "getClientProduct[$index] $part")
        }
        return response
    }

    companion object {
        const val TAG = "HandoffProduct"
        private const val CLIENT_PRODUCT_PATH = "client_product"
        private const val MAX_LOGGED_BYTES = 256L * 1024
        private const val LOG_CHUNK = 3_000
    }
}
