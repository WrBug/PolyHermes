package com.wrbug.polymarketbot.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Polymarket Order Message DTO
 * 根据 https://docs.polymarket.com/developers/CLOB/websocket/user-channel#order-message
 */
data class OrderMessageDto(
    @JsonProperty("asset_id")
    val assetId: String,              // asset ID (token ID) of order
    
    @JsonProperty("associate_trades")
    val associateTrades: List<String>?, // array of ids referencing trades that the order has been included in
    
    @JsonProperty("event_type")
    val eventType: String,             // "order"
    
    val id: String,                    // order id
    val market: String,                // condition ID of market
    
    @JsonProperty("order_owner")
    val orderOwner: String,            // owner of order
    
    @JsonProperty("original_size")
    val originalSize: String,          // original order size
    val outcome: String,               // outcome
    val owner: String,                 // owner of orders
    val price: String,                 // price of order
    val side: String,                  // BUY/SELL
    
    @JsonProperty("size_matched")
    val sizeMatched: String,           // size of order that has been matched
    val timestamp: String,             // time of event
    val type: String                   // PLACEMENT/UPDATE/CANCELLATION
)

/**
 * 订单推送消息（统一格式）
 */
data class OrderPushMessage(
    val accountId: Long,               // 账户 ID
    val accountName: String,           // 账户名称
    val order: OrderMessageDto,        // 订单信息（来自 WebSocket）
    val orderDetail: OrderDetailDto? = null,  // 订单详情（通过 API 获取）
    val timestamp: Long = System.currentTimeMillis(),  // 推送时间戳
    // 跟单相关字段（可选，仅在跟单触发的订单时提供）
    val leaderName: String? = null,    // Leader 名称（备注）
    val configName: String? = null    // 跟单配置名
)

/**
 * 订单详情（通过 API 获取）
 */
data class OrderDetailDto(
    val id: String,                    // 订单 ID
    val market: String,                 // 市场 ID (condition ID)
    val side: String,                   // BUY/SELL
    val price: String,                  // 价格
    val size: String,                   // 订单大小
    val filled: String,                 // 已成交数量
    val status: String,                 // 订单状态
    val createdAt: String,             // 创建时间（ISO 8601 格式）
    val marketName: String? = null,     // 市场名称（通过 Data API 获取）
    val marketSlug: String? = null,     // 市场 slug
    val marketIcon: String? = null       // 市场图标
)

