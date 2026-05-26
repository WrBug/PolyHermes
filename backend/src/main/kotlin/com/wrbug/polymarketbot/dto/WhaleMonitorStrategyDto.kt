package com.wrbug.polymarketbot.dto

/**
 * 大单监听策略创建请求
 * 金额与价格使用 String，后端转为 BigDecimal
 */
data class WhaleMonitorStrategyCreateRequest(
    val accountId: Long = 0L,
    val name: String? = null,
    /** 监听市场 conditionId 列表 */
    val conditionIds: List<String> = emptyList(),
    val windowSeconds: Int = 10,
    /** 触发阈值金额 */
    val thresholdAmount: String = "0",
    /** 固定下单金额 */
    val orderAmount: String = "0",
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val cooldownSeconds: Int = 60,
    val enabled: Boolean = true
)

/**
 * 大单监听策略更新请求
 */
data class WhaleMonitorStrategyUpdateRequest(
    val strategyId: Long = 0L,
    val name: String? = null,
    val conditionIds: List<String>? = null,
    val windowSeconds: Int? = null,
    val thresholdAmount: String? = null,
    val orderAmount: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val cooldownSeconds: Int? = null,
    val enabled: Boolean? = null
)

/**
 * 大单监听策略列表请求
 */
data class WhaleMonitorStrategyListRequest(
    val accountId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 策略关联市场展示信息（来自 markets 表缓存）
 */
data class WhaleMonitorMarketDto(
    val conditionId: String = "",
    val title: String = "",
    val slug: String? = null,
    val category: String? = null,
    val image: String? = null,
    val icon: String? = null
)

/**
 * 大单监听策略 DTO（列表与详情）
 */
data class WhaleMonitorStrategyDto(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val name: String? = null,
    val conditionIds: List<String> = emptyList(),
    /** 监听市场展示信息（与 conditionIds 顺序一致，保存时写入 markets 缓存） */
    val markets: List<WhaleMonitorMarketDto> = emptyList(),
    val windowSeconds: Int = 10,
    val thresholdAmount: String = "0",
    val orderAmount: String = "0",
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val cooldownSeconds: Int = 60,
    val enabled: Boolean = true,
    val lastTriggerAt: Long? = null,
    val triggerCount: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 大单监听策略列表响应
 */
data class WhaleMonitorStrategyListResponse(
    val list: List<WhaleMonitorStrategyDto> = emptyList()
)

/**
 * 大单监听策略删除请求
 */
data class WhaleMonitorStrategyDeleteRequest(
    val strategyId: Long = 0L
)

/**
 * 触发记录列表请求
 */
data class WhaleMonitorTriggerListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: String? = null,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 触发记录 DTO
 */
data class WhaleMonitorTriggerDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val conditionId: String = "",
    val tokenId: String = "",
    val side: String = "BUY",
    val triggerVolume: String = "0",
    val orderPrice: String = "0",
    val orderSize: String = "0",
    val orderAmount: String = "0",
    val orderId: String? = null,
    val status: String = "success",
    val failReason: String? = null,
    val createdAt: Long = 0L
)

/**
 * 触发记录分页响应
 */
data class WhaleMonitorTriggerListResponse(
    val list: List<WhaleMonitorTriggerDto> = emptyList(),
    val total: Long = 0L
)
