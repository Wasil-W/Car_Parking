package dev.wasil.permit.data.api

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

fun buildPermitApi(baseUrl: HttpUrl, client: OkHttpClient): PermitApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(PermitJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PermitApi::class.java)
