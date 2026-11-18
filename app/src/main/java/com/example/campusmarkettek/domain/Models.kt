package com.example.campusmarkettek.domain

/** หมวดสินค้า (หนังสือ / อุปกรณ์คอมพิวเตอร์ / เครื่องเขียน และหมวดย่อย) */
data class Category(
    val id: Int,
    val parentId: Int?,
    val nameTh: String,
    val sort: Int,
)
