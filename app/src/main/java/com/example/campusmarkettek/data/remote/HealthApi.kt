package com.example.campusmarkettek.data.remote

import retrofit2.http.GET

data class HealthResponse(
    val status: String,
    val database: String,
)

interface HealthApi {
    @GET("health")
    suspend fun health(): HealthResponse
}
