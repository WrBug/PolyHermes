package com.wrbug.polymarketbot.service.auth

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 票据服务
 * 用于生成短期有效的一次性票据，避免在 WebSocket URL 中暴露 JWT
 */
@Service
class WebSocketTicketService {

    companion object {
        // 票据有效期（30秒）
        private const val TICKET_VALIDITY_MS = 30_000L

        // 票据长度（32字节 = 64个十六进制字符）
        private const val TICKET_LENGTH = 32
    }

    private val secureRandom = SecureRandom()

    // 存储票据：ticket -> TicketInfo
    private val tickets = ConcurrentHashMap<String, TicketInfo>()

    /**
     * 票据信息
     */
    data class TicketInfo(
        val username: String,
        val createdAt: Long,
        val expiresAt: Long
    )

    /**
     * 为用户生成 WebSocket 连接票据
     * @param username 用户名
     * @return 一次性票据
     */
    fun generateTicket(username: String): String {
        // 清理过期票据
        cleanupExpiredTickets()

        // 生成随机票据
        val bytes = ByteArray(TICKET_LENGTH)
        secureRandom.nextBytes(bytes)
        val ticket = bytes.joinToString("") { "%02x".format(it) }

        val now = System.currentTimeMillis()
        tickets[ticket] = TicketInfo(
            username = username,
            createdAt = now,
            expiresAt = now + TICKET_VALIDITY_MS
        )

        return ticket
    }

    /**
     * 验证并消费票据（一次性使用）
     * @param ticket 票据
     * @return 用户名，如果票据无效则返回 null
     */
    fun validateAndConsumeTicket(ticket: String): String? {
        val ticketInfo = tickets.remove(ticket) ?: return null

        // 检查是否过期
        if (System.currentTimeMillis() > ticketInfo.expiresAt) {
            return null
        }

        return ticketInfo.username
    }

    /**
     * 清理过期票据
     */
    private fun cleanupExpiredTickets() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
    }
}
