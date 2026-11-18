package com.example.campusmarkettek.data.remote

import retrofit2.http.GET

/** แถวเดียวกับตาราง categories (parent_id → parentId, name_th → nameTh) */
data class CategoryDto(
    val id: Int,
    val parentId: Int?,
    val nameTh: String,
    val sort: Int,
)

interface CategoryApi {
    @GET("categories")
    suspend fun categories(): List<CategoryDto>
}
