package dev.wasil.permit.data.auth

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenStore.token
        if (token == null || request.url.encodedPath.endsWith("/ssp/login_check")) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}
