package com.wrbug.polymarketbot.dto

/**
 * 尾盘策略创建请求
 * 金额与价格使用 String，后端转为 BigDecimal
 */
data class CryptoTailStrategyCreateRequest(
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val intervalSeconds: Int = 300,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String? = null,
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    val enabled: Boolean = true
)

/**
 * 尾盘策略更新请求
 */
data class CryptoTailStrategyUpdateRequest(
    val strategyId: Long = 0L,
    val name: String? = null,
    val windowStartSeconds: Int? = null,
    val windowEndSeconds: Int? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val amountMode: String? = null,
    val amountValue: String? = null,
    val enabled: Boolean? = null
)

/**
 * 尾盘策略列表请求
 */
data class CryptoTailStrategyListRequest(
    val accountId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 尾盘策略 DTO（列表与详情）
 */
data class CryptoTailStrategyDto(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val marketTitle: String? = null,
    val intervalSeconds: Int = 0,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    val enabled: Boolean = true,
    val lastTriggerAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 尾盘策略列表响应
 */
data class CryptoTailStrategyListResponse(
    val list: List<CryptoTailStrategyDto> = emptyList()
)

/**
 * 尾盘策略删除请求
 */
data class CryptoTailStrategyDeleteRequest(
    val strategyId: Long = 0L
)

/**
 * 触发记录列表请求
 */
data class CryptoTailStrategyTriggerListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: String? = null
)

/**
 * 触发记录 DTO
 */
data class CryptoTailStrategyTriggerDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val periodStartUnix: Long = 0L,
    val marketTitle: String? = null,
    val outcomeIndex: Int = 0,
    val triggerPrice: String = "0",
    val amountUsdc: String = "0",
    val orderId: String? = null,
    val status: String = "success",
    val failReason: String? = null,
    val createdAt: Long = 0L
)

/**
 * 触发记录分页响应
 */
data class CryptoTailStrategyTriggerListResponse(
    val list: List<CryptoTailStrategyTriggerDto> = emptyList(),
    val total: Long = 0L
)

/**
 * 5/15 分钟市场项（供前端选择市场）
 */
data class CryptoTailMarketOptionDto(
    val slug: String = "",
    val title: String = "",
    val intervalSeconds: Int = 0,
    val periodStartUnix: Long = 0L,
    val endDate: String? = null
)
