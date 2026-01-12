package com.wrbug.polymarketbot.dto

/**
 * 模板创建请求
 */
data class TemplateCreateRequest(
    val templateName: String,
    val copyMode: String = "RATIO",  // "RATIO" 或 "FIXED"
    val copyRatio: String? = null,  // 仅在 copyMode="RATIO" 时生效
    val fixedAmount: String? = null,  // 仅在 copyMode="FIXED" 时生效
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    // 过滤条件
    val minOrderDepth: String? = null,  // 最小订单深度（USDC金额），NULL表示不启用
    val maxSpread: String? = null,  // 最大价差（绝对价格），NULL表示不启用
    val minPrice: String? = null,  // 最低价格（可选），NULL表示不限制最低价
    val maxPrice: String? = null,  // 最高价格（可选），NULL表示不限制最高价
    val pushFilteredOrders: Boolean? = null  // 推送已过滤订单（默认关闭）
)

/**
 * 模板更新请求
 */
data class TemplateUpdateRequest(
    val templateId: Long,
    val templateName: String? = null,  // 模板名称（可选）
    val copyMode: String? = null,
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    // 过滤条件
    val minOrderDepth: String? = null,  // 最小订单深度（USDC金额），NULL表示不启用
    val maxSpread: String? = null,  // 最大价差（绝对价格），NULL表示不启用
    val minPrice: String? = null,  // 最低价格（可选），NULL表示不限制最低价
    val maxPrice: String? = null,  // 最高价格（可选），NULL表示不限制最高价
    val pushFilteredOrders: Boolean? = null  // 推送已过滤订单（默认关闭）
)

/**
 * 模板删除请求
 */
data class TemplateDeleteRequest(
    val templateId: Long
)

/**
 * 模板复制请求
 */
data class TemplateCopyRequest(
    val templateId: Long,
    val templateName: String,
    val copyMode: String? = null,
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    // 过滤条件
    val minOrderDepth: String? = null,  // 最小订单深度（USDC金额），NULL表示不启用
    val maxSpread: String? = null,  // 最大价差（绝对价格），NULL表示不启用
    val minPrice: String? = null,  // 最低价格（可选），NULL表示不限制最低价
    val maxPrice: String? = null,  // 最高价格（可选），NULL表示不限制最高价
    val pushFilteredOrders: Boolean? = null  // 推送已过滤订单（默认关闭）
)

/**
 * 模板详情请求
 */
data class TemplateDetailRequest(
    val templateId: Long
)

/**
 * 模板信息响应
 */
data class TemplateDto(
    val id: Long,
    val templateName: String,
    val copyMode: String,
    val copyRatio: String,
    val fixedAmount: String?,
    val maxOrderSize: String,
    val minOrderSize: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val priceTolerance: String,
    val delaySeconds: Int,
    val pollIntervalSeconds: Int,
    val useWebSocket: Boolean,
    val websocketReconnectInterval: Int,
    val websocketMaxRetries: Int,
    val supportSell: Boolean,
    // 过滤条件
    val minOrderDepth: String?,
    val maxSpread: String?,
    val minPrice: String?,  // 最低价格（可选），NULL表示不限制最低价
    val maxPrice: String?,  // 最高价格（可选），NULL表示不限制最高价
    val pushFilteredOrders: Boolean,  // 推送已过滤订单（默认关闭）
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 模板列表响应
 */
data class TemplateListResponse(
    val list: List<TemplateDto>,
    val total: Long
)

