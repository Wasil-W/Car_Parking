package dev.wasil.permit.data.api

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

object ApiConstants {
    const val BASE_URL = "https://api.parkeervergunningen.egisparkingservices.nl/api/"
    const val PRODUCT_ID = 5807976L
    const val LOGIN_PATH = "/api/ssp/login_check"
}

interface PermitApi {
    @POST("ssp/login_check")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("v1/client_product/{productId}")
    suspend fun getClientProduct(@Path("productId") productId: Long): ClientProductResponse

    @POST("v1/ssp/parking_session/activate")
    suspend fun activate(@Body body: ActivateRequest): ActivateResponse
}

/**
 * Whether a failed call was the site saying **no**, rather than the app never
 * getting to ask.
 *
 * The two have to be told apart because they deserve opposite sentences. "Check
 * the username and password" is unhelpful when the site is down — the
 * credentials are fine — and "the site is unreachable" is worse than unhelpful
 * when the password is simply wrong, because it sends someone to look for a
 * fault that is not there. Both were shown as one message until v0.6.8, for the
 * honest reason that the app could not actually tell: with a cached token the
 * request never 401ed in the first place.
 *
 * 403 counts as a refusal alongside 401: an account the site will not serve is
 * a credential problem from where the user is standing, whatever the status
 * line calls it.
 */
fun rejectedCredentials(error: Throwable): Boolean =
    error is HttpException && (error.code() == 401 || error.code() == 403)

fun buildPermitApi(baseUrl: HttpUrl, client: OkHttpClient): PermitApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(PermitJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PermitApi::class.java)
