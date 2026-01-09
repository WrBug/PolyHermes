package com.wrbug.polymarketbot.dto

import com.google.gson.annotations.SerializedName

/**
 * Activity WebSocket 交易消息 DTO
 * 根据 Polymarket RTDS Activity WebSocket API 格式定义
 */
data class ActivityTradeMessage(
    val topic: String = "",                    // "activity"
    val type: String = "",                     // "trades"
    val timestamp: Long? = null,               // 消息时间戳（可选）
    @SerializedName("connection_id")
    val connectionId: String? = null,          // 连接 ID（可选，由服务器返回）
    val payload: ActivityTradePayload = ActivityTradePayload()  // 交易数据
)

/**
 * Activity Trade Payload
 */
data class ActivityTradePayload(
    val asset: String = "",                    // Token ID (用于下单)
    
    @SerializedName("conditionId")
    val conditionId: String = "",              // Market condition ID
    
    @SerializedName("eventSlug")
    val eventSlug: String? = null,             // 事件 slug
    
    val slug: String? = null,                  // 市场 slug
    
    val outcome: String? = null,               // 结果方向 (Yes/No/Up/Down)
    
    @SerializedName("outcomeIndex")
    val outcomeIndex: Int? = null,             // 结果索引 (0=Yes/Up, 1=No/Down) - 优先使用此字段
    
    val side: String = "",                     // 交易方向 (BUY/SELL)
    
    // price 和 size 可能是数字或字符串，使用 Any 类型，后续转换为 String
    val price: Any? = null,                    // 交易价格
    
    val size: Any? = null,                     // 交易数量 (shares)
    
    val timestamp: Any? = null,                // Unix 时间戳（可能是秒或毫秒，可能是数字或字符串）
    
    @SerializedName("transactionHash")
    val transactionHash: String? = null,       // 交易哈希
    
    val trader: ActivityTrader? = null,        // 交易者信息对象（优先）
    
    @SerializedName("proxyWallet")
    val proxyWallet: String? = null,           // 交易者地址（fallback，如果 trader 不存在）
    
    val name: String? = null                   // 交易者名称（fallback，如果 trader 不存在）
)

/**
 * Activity Trader 信息
 */
data class ActivityTrader(
    val name: String? = null,             // 交易者用户名（可选）
    val address: String? = null           // 交易者钱包地址 ⭐ 关键字段
)

