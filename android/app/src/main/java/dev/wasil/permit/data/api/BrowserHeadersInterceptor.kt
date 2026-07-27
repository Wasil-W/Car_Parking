package dev.wasil.permit.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The permit API rejects requests (403) that don't look like they come from
 * the official web frontend, even with valid credentials. These three headers
 * plus accept are mandatory on every call.
 */
class BrowserHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("accept", "application/json, text/plain, */*")
            .header("origin", "https://parkeervergunningen.amsterdam.nl")
            .header("referer", "https://parkeervergunningen.amsterdam.nl/")
            .header(
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            )
            .build()
        return chain.proceed(request)
    }
}
