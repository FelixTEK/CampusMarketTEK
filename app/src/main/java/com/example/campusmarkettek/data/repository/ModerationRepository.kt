package com.example.campusmarkettek.data.repository

import com.example.campusmarkettek.data.remote.AdminReportDto
import com.example.campusmarkettek.data.remote.AdminUserDto
import com.example.campusmarkettek.data.remote.AdminUserListDto
import com.example.campusmarkettek.data.remote.BanRequest
import com.example.campusmarkettek.data.remote.CloseReportRequest
import com.example.campusmarkettek.data.remote.ModerationApi
import com.example.campusmarkettek.data.remote.ReportRequest
import com.example.campusmarkettek.data.remote.ReviewDto
import com.example.campusmarkettek.data.remote.ReviewRequest
import com.example.campusmarkettek.data.remote.SellerProfileDto
import javax.inject.Inject
import javax.inject.Singleton

/** รีวิว รายงาน โปรไฟล์ผู้ขาย และเครื่องมือผู้ดูแล — ทุกอย่างให้ server ตัดสิน ไม่เก็บในเครื่อง (ต้องมีเน็ต) */
@Singleton
class ModerationRepository @Inject constructor(private val api: ModerationApi) {
    suspend fun profile(id: String): SellerProfileDto = api.profile(id)

    suspend fun review(dealId: String, rating: Int, comment: String): ReviewDto =
        api.review(dealId, ReviewRequest(rating, comment.trim().ifEmpty { null }))

    /** รีวิวของดีล (null = ยังไม่มี) */
    suspend fun reviewOf(dealId: String): ReviewDto? = api.reviewOf(dealId).review

    suspend fun report(targetType: String, targetId: String, reason: String, detail: String) {
        api.report(ReportRequest(targetType, targetId, reason, detail.trim().ifEmpty { null }))
    }

    suspend fun reports(status: String): List<AdminReportDto> = api.adminReports(status).items
    suspend fun reportById(id: String): AdminReportDto = api.adminReport(id)
    suspend fun closeReport(id: String, note: String) { api.closeReport(id, CloseReportRequest(note)) }
    suspend fun adminUser(id: String): AdminUserDto = api.adminUser(id)
    suspend fun ban(id: String, reason: String) { api.ban(id, BanRequest(reason)) }
    suspend fun unban(id: String) { api.unban(id) }
    suspend fun users(q: String, filter: String): AdminUserListDto = api.adminUsers(q.trim(), filter)
    suspend fun promote(id: String) { api.promote(id) }
    suspend fun demote(id: String) { api.demote(id) }
}
