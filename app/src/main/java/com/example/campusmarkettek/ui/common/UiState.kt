package com.example.campusmarkettek.ui.common

/** สถานะหน้าจอมาตรฐาน — ทุกหน้าที่โหลดข้อมูลใช้ชุดเดียวกัน (Loading / Error / Empty / Success) */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}
