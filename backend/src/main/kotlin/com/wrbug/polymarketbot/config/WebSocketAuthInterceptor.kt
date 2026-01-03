package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.auth.WebSocketTicketService
import com.wrbug.polymarketbot.util.JwtUtils
import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

/**
 * WebSocket 握手拦截器
 * 优先使用短期票据验证，其次使用 JWT token
 */
@Component
class WebSocketAuthInterceptor(
    private val jwtUtils: JwtUtils,
    private val userRepository: UserRepository,
    private val webSocketTicketService: WebSocketTicketService
) : HandshakeInterceptor {
    
    private val logger = LoggerFactory.getLogger(WebSocketAuthInterceptor::class.java)
    
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        // 优先使用票据验证（推荐方式，不暴露 JWT）
        val ticket = getTicketFromRequest(request)
        if (ticket != null) {
            val username = webSocketTicketService.validateAndConsumeTicket(ticket)
            if (username != null) {
                attributes["username"] = username
                logger.debug("WebSocket 连接票据认证成功: username=$username")
                return true
            }
            logger.warn("WebSocket 连接票据验证失败（可能已过期或已使用）")
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED)
            return false
        }

        // 兼容旧方式：使用 JWT token（不推荐，但保持向后兼容）
        val token = getTokenFromRequest(request)

        if (token == null) {
            logger.warn("WebSocket 连接缺少认证令牌: ${request.uri.path}")
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED)
            return false
        }

        // 验证 token
        if (!jwtUtils.validateToken(token)) {
            logger.warn("WebSocket 连接 token 验证失败")
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED)
            return false
        }

        // 验证tokenVersion（检查token是否因密码修改而失效）
        val username = jwtUtils.getUsernameFromToken(token)
        if (username != null) {
            val user = userRepository.findByUsername(username)
            if (user != null) {
                val tokenVersion = jwtUtils.getTokenVersionFromToken(token)
                if (tokenVersion == null || tokenVersion != user.tokenVersion) {
                    logger.warn("WebSocket 连接 token 版本不匹配，token已失效: username=$username")
                    response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    return false
                }
            }

            // 获取用户名并存入 attributes，供后续使用
            attributes["username"] = username
            logger.debug("WebSocket 连接 JWT 认证成功: username=$username")
        } else {
            logger.warn("WebSocket 连接无法获取用户名")
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED)
            return false
        }

        return true
    }
    
    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?
    ) {
        // 握手后处理（如果需要）
    }
    
    /**
     * 从请求中获取票据
     */
    private fun getTicketFromRequest(request: ServerHttpRequest): String? {
        val queryParams = request.uri.query ?: return null
        val params = queryParams.split("&")
        for (param in params) {
            val parts = param.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "ticket") {
                return parts[1]
            }
        }
        return null
    }

    /**
     * 从请求中获取 token（兼容旧方式）
     * 支持从查询参数 token 或请求头 Authorization 获取
     */
    private fun getTokenFromRequest(request: ServerHttpRequest): String? {
        // 优先从查询参数获取
        val queryParams = request.uri.query
        if (queryParams != null) {
            val params = queryParams.split("&")
            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "token") {
                    return parts[1]
                }
            }
        }

        // 从请求头获取
        val authHeader = request.headers.getFirst("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7)
        }

        return null
    }
}

