package com.example.campusmarkettek.data.remote

import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** การ์ดสินค้าในหน้าแรก / ผลค้นหา / รายการโปรด (ข้อมูลจาก listings + users + faculties) */
data class ListingCardDto(
    val id: String,
    val title: String,
    val price: Int,
    val status: String,
    val categoryId: Int,
    /** URL สัมพัทธ์ของรูปปก */
    val cover: String?,
    val sellerName: String,
    val sellerFaculty: String?,
    val createdAt: String,
    val isFavorite: Boolean,
)

data class ListingPageDto(val items: List<ListingCardDto>, val page: Int, val hasMore: Boolean)

data class CountDto(val count: Int)

interface BrowseApi {
    @GET("listings")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("category") category: Int? = null,
        @Query("faculty") faculty: Int? = null,
        /** "1,2,3" */
        @Query("tags") tags: String? = null,
        @Query("minPrice") minPrice: Int? = null,
        @Query("maxPrice") maxPrice: Int? = null,
        /** relevant | new | price_asc | price_desc */
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
    ): ListingPageDto

    @GET("listings/count")
    suspend fun count(
        @Query("q") q: String? = null,
        @Query("category") category: Int? = null,
        @Query("faculty") faculty: Int? = null,
        @Query("tags") tags: String? = null,
        @Query("minPrice") minPrice: Int? = null,
        @Query("maxPrice") maxPrice: Int? = null,
    ): CountDto

    @GET("favorites")
    suspend fun favorites(): List<ListingCardDto>

    @PUT("favorites/{id}")
    suspend fun addFavorite(@Path("id") id: String)

    @DELETE("favorites/{id}")
    suspend fun removeFavorite(@Path("id") id: String)
}
