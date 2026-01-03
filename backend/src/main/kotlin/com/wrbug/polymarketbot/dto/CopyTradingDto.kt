package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

/**
 * 跟单创建请求
 * 支持两种方式：
 * 1. 提供 templateId：从模板填充配置，可以覆盖部分字段
 * 2. 不提供 templateId：手动输入所有配置参数
 */
data class CopyTradingCreateRequest(
    val accountId: Long,
    val leaderId: Long,
    val enabled: Boolean = true,
    // 可选：如果提供 templateId，则从模板填充配置（可以覆盖）
    val templateId: Long? = null,
    // 跟单配置参数（如果提供 templateId，这些字段可选，用于覆盖模板值）
    val copyMode: String? = null,  // "RATIO" 或 "FIXED"
    val copyRatio: String? = null,  // 仅在 copyMode="RATIO" 时生效
    val fixedAmount: String? = null,  // 仅在 copyMode="FIXED" 时生效
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,  // 百分比
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
    // 最大仓位配置
    val maxPositionValue: String? = null,  // 最大仓位金额（USDC），NULL表示不启用
    val maxPositionCount: Int? = null,  // 最大仓位数量，NULL表示不启用
    // 新增配置字段
    val configName: String? = null,  // 配置名（可选）
    val pushFailedOrders: Boolean? = null  // 推送失败订单（可选）
)

/**
 * 跟单更新请求
 */
data class CopyTradingUpdateRequest(
    val copyTradingId: Long,
    val enabled: Boolean? = null,
    // 跟单配置参数（可选，只更新提供的字段）
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
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,  // 最低价格（可选），NULL表示不限制最低价
    val maxPrice: String? = null,  // 最高价格（可选），NULL表示不限制最高价
    // 最大仓位配置
    val maxPositionValue: String? = null,  // 最大仓位金额（USDC），NULL表示不启用
    val maxPositionCount: Int? = null,  // 最大仓位数量，NULL表示不启用
    // 新增配置字段
    val configName: String? = null,  // 配置名（可选，但提供时必须非空）
    val pushFailedOrders: Boolean? = null  // 推送失败订单（可选）
)

/**
 * 跟单列表请求
 */
data class CopyTradingListRequest(
    val accountId: Long? = null,
    val leaderId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 跟单状态更新请求
 */
data class CopyTradingUpdateStatusRequest(
    val copyTradingId: Long,
    val enabled: Boolean
)

/**
 * 跟单删除请求
 */
data class CopyTradingDeleteRequest(
    val copyTradingId: Long
)

/**
 * 查询钱包绑定的模板请求
 */
data class AccountTemplatesRequest(
    val accountId: Long
)

/**
 * 跟单信息响应
 */
data class CopyTradingDto(
    val id: Long,
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean,
    // 跟单配置参数
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
    // 最大仓位配置
    val maxPositionValue: String? = null,  // 最大仓位金额（USDC），NULL表示不启用
    val maxPositionCount: Int? = null,  // 最大仓位数量，NULL表示不启用
    // 新增配置字段
    val configName: String? = null,  // 配置名（可选）
    val pushFailedOrders: Boolean = false,  // 推送失败订单（默认关闭）
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 跟单列表响应
 */
data class CopyTradingListResponse(
    val list: List<CopyTradingDto>,
    val total: Long
)

/**
 * 钱包绑定的跟单配置信息（已废弃，保留用于兼容）
 */
data class AccountTemplateDto(
    val templateId: Long? = null,  // 已废弃
    val templateName: String? = null,  // 已废弃
    val copyTradingId: Long,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean
)

/**
 * 钱包绑定的模板列表响应
 */
data class AccountTemplatesResponse(
    val list: List<AccountTemplateDto>,
    val total: Long
)

