package com.wrbug.polymarketbot.dto

/**
 * 登录响应
 */
data class LoginResponse(
    val token: String
)

/**
 * 检查首次使用响应
 */
data class CheckFirstUseResponse(
    val isFirstUse: Boolean
)

/**
 * WebSocket 票据响应
 */
data class WebSocketTicketResponse(
    val ticket: String
)

