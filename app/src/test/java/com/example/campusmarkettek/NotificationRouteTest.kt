package com.example.campusmarkettek

import com.example.campusmarkettek.data.local.entity.NotificationEntity
import com.example.campusmarkettek.data.repository.targetRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRouteTest {
    private fun n(conversationId: String?, listingId: String?) = NotificationEntity(
        id = 1, type = "DEAL_OFFER", body = "x", refId = null,
        conversationId = conversationId, listingId = listingId, isRead = false, createdAt = 0,
    )

    @Test fun `แตะแล้วไปห้องแชตของดีลก่อน`() = assertEquals("chat/c1", n("c1", "l1").targetRoute())

    @Test fun `ไม่มีห้องก็ไปหน้าประกาศ`() = assertEquals("listing/l1", n(null, "l1").targetRoute())

    @Test fun `ไม่มีทั้งคู่ก็ไม่ไปไหน`() = assertNull(n(null, null).targetRoute())
}
