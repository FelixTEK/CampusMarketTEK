package com.example.campusmarkettek.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/** เดือนหนึ่งในกราฟ: month = "2026-11" · count = จำนวนดีลที่สำเร็จ · value = ผลรวมราคาประกาศ (บาท) */
data class MonthStatDto(val month: String, val count: Int, val value: Int)
data class MonthCountDto(val month: String, val count: Int)

/** byStatus: AVAILABLE | RESERVED | SOLD | CANCELLED | RETURNED | TAKEN_DOWN → จำนวนประกาศ */
data class SellerStatsDto(
    val sold: Int,
    val soldValue: Int,
    val closeRate: Int?,
    val rating: RatingDto?,
    val byStatus: Map<String, Int>,
    val monthly: List<MonthStatDto>,
)

data class BuyerStatsDto(val bought: Int, val spent: Int, val monthly: List<MonthStatDto>)
data class MyStatsDto(val months: Int, val seller: SellerStatsDto, val buyer: BuyerStatsDto)

data class UserTotalsDto(val total: Int, val verified: Int, val banned: Int, val kmitl: Int, val outsiders: Int)

data class AdminStatsDto(
    val months: Int,
    val users: UserTotalsDto,
    val listings: Map<String, Int>,
    val deals: Map<String, Int>,
    val openReports: Int,
    val pendingVerifications: Int,
    val totalValue: Int,
    val monthly: List<MonthStatDto>,
    val newUsers: List<MonthCountDto>,
)

interface StatsApi {
    @GET("stats/me")
    suspend fun mine(@Query("months") months: Int): MyStatsDto

    @GET("admin/stats")
    suspend fun admin(@Query("months") months: Int): AdminStatsDto
}
