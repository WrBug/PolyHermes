package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * Polygon RPC 节点配置实体
 * 用于存储用户配置的 RPC 节点信息
 */
@Entity
@Table(name = "rpc_node_config")
data class RpcNodeConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "provider_type", nullable = false, length = 50)
    val providerType: String,  // 服务商类型: ALCHEMY, INFURA, QUICKNODE, CHAINSTACK, GETBLOCK, CUSTOM, PUBLIC
    
    @Column(name = "name", nullable = false, length = 100)
    val name: String,  // 节点名称
    
    @Column(name = "http_url", nullable = false, length = 500)
    val httpUrl: String,  // HTTP RPC URL
    
    @Column(name = "ws_url", length = 500)
    val wsUrl: String? = null,  // WebSocket URL (可选)
    
    @Column(name = "api_key", length = 200)
    val apiKey: String? = null,  // API Key (加密存储)
    
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,  // 是否启用
    
    @Column(name = "priority", nullable = false)
    var priority: Int = 0,  // 优先级(数字越小优先级越高)
    
    @Column(name = "last_check_time")
    var lastCheckTime: Long? = null,  // 最后检查时间(毫秒时间戳)
    
    @Column(name = "last_check_status", length = 20)
    var lastCheckStatus: String? = null,  // 最后检查状态: HEALTHY, UNHEALTHY, UNKNOWN
    
    @Column(name = "response_time_ms")
    var responseTimeMs: Int? = null,  // 最后一次响应时间(毫秒)
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

/**
 * RPC 节点健康状态枚举
 */
enum class NodeHealthStatus {
    HEALTHY,    // 健康
    UNHEALTHY,  // 不健康
    UNKNOWN     // 未知
}

/**
 * RPC 节点服务商类型枚举
 */
enum class RpcProviderType {
    ALCHEMY,
    INFURA,
    QUICKNODE,
    CHAINSTACK,
    GETBLOCK,
    CUSTOM,
    PUBLIC
}
