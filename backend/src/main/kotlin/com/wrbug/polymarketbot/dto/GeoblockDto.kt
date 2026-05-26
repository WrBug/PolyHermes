package com.wrbug.polymarketbot.dto

/**
 * Polymarket Geoblock API 原始响应
 */
data class PolymarketGeoblockApiResponse(
    val blocked: Boolean = false,
    val ip: String = "",
    val country: String = "",
    val region: String = ""
)

/**
 * 地域限制检查结果（返回给前端）
 */
data class GeoblockCheckDto(
    val blocked: Boolean,
    val ip: String,
    val country: String,
    val region: String,
    val checkedAt: Long,
    val source: String = "server"
)
