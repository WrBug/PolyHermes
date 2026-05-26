package com.wrbug.polymarketbot.dto

/**
 * 代理配置 DTO（用于返回给前端，不包含密码）
 */
data class ProxyConfigDto(
    val id: Long?,
    val type: String,  // HTTP, CLASH, SS
    val enabled: Boolean,
    val host: String?,
    val port: Int?,
    val username: String?,
    val subscriptionUrl: String?,
    val lastSubscriptionUpdate: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 创建/更新 HTTP 代理配置请求
 */
data class HttpProxyConfigRequest(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null  // 更新时如果为空则不更新密码
)

/**
 * 创建/更新订阅代理配置请求（第二阶段功能）
 */
data class SubscriptionProxyConfigRequest(
    val enabled: Boolean,
    val subscriptionUrl: String,
    val type: String  // CLASH 或 SS
)

/**
 * 代理检查时的 Polymarket 地域限制检测结果
 */
data class ProxyCheckGeoblockResult(
    val checked: Boolean,
    val blocked: Boolean? = null,
    val ip: String? = null,
    val country: String? = null,
    val region: String? = null,
    val message: String? = null
)

/**
 * 代理检查响应
 */
data class ProxyCheckResponse(
    val success: Boolean,
    val message: String,
    val responseTime: Long? = null,  // 响应时间（毫秒）
    val latency: Long? = null,  // 延迟（毫秒），与 responseTime 相同，用于前端显示
    val geoblock: ProxyCheckGeoblockResult? = null
) {
    companion object {
        fun create(
            success: Boolean,
            message: String,
            responseTime: Long? = null,
            geoblock: ProxyCheckGeoblockResult? = null
        ): ProxyCheckResponse {
            return ProxyCheckResponse(
                success = success,
                message = message,
                responseTime = responseTime,
                latency = responseTime,
                geoblock = geoblock
            )
        }
    }
}

