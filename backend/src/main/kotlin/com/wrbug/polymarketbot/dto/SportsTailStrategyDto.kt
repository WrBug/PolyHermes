package com.wrbug.polymarketbot.dto

/**
 * 体育尾盘策略 DTO
 */
data class SportsTailStrategyDto(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val accountName: String? = null,
    val conditionId: String = "",
    val marketTitle: String? = null,
    val eventSlug: String? = null,
    val triggerPrice: String = "",
    val amountMode: String = "FIXED",
    val amountValue: String = "",
    val takeProfitPrice: String? = null,
    val stopLossPrice: String? = null,

    /** 成交信息 */
    val filled: Boolean = false,
    val filledPrice: String? = null,
    val filledOutcomeIndex: Int? = null,
    val filledOutcomeName: String? = null,
    val filledAmount: String? = null,
    val filledShares: String? = null,
    val filledAt: Long? = null,

    /** 卖出信息 */
    val sold: Boolean = false,
    val sellPrice: String? = null,
    val sellType: String? = null,
    val sellAmount: String? = null,
    val realizedPnl: String? = null,
    val soldAt: Long? = null,

    /** 实时价格（未成交时返回） */
    val realtimeYesPrice: String? = null,
    val realtimeNoPrice: String? = null,

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 策略列表请求
 */
data class SportsTailStrategyListRequest(
    val accountId: Long? = null,
    val sport: String? = null
)

/**
 * 策略列表响应
 */
data class SportsTailStrategyListResponse(
    val list: List<SportsTailStrategyDto> = emptyList()
)

/**
 * 策略创建请求
 */
data class SportsTailStrategyCreateRequest(
    val accountId: Long = 0L,
    val conditionId: String = "",
    val marketTitle: String = "",
    val eventSlug: String? = null,
    val triggerPrice: String = "",
    val amountMode: String = "FIXED",
    val amountValue: String = "",
    val takeProfitPrice: String? = null,
    val stopLossPrice: String? = null
)

/**
 * 策略创建响应
 */
data class SportsTailStrategyCreateResponse(
    val id: Long = 0L
)

/**
 * 策略删除请求
 */
data class SportsTailStrategyDeleteRequest(
    val id: Long = 0L
)

/**
 * 策略触发记录 DTO
 */
data class SportsTailTriggerDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,

    /** 市场信息 */
    val marketTitle: String? = null,
    val conditionId: String = "",

    /** 买入信息 */
    val buyPrice: String = "",
    val outcomeIndex: Int = 0,
    val outcomeName: String? = null,
    val buyAmount: String = "",
    val buyShares: String? = null,
    val buyStatus: String = "PENDING",

    /** 卖出信息 */
    val sellPrice: String? = null,
    val sellType: String? = null,
    val sellAmount: String? = null,
    val sellStatus: String? = null,

    /** 盈亏 */
    val realizedPnl: String? = null,

    /** 时间 */
    val triggeredAt: Long = 0L,
    val soldAt: Long? = null
)

/**
 * 触发记录列表请求
 */
data class SportsTailTriggerListRequest(
    val accountId: Long? = null,
    val status: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * 触发记录列表响应
 */
data class SportsTailTriggerListResponse(
    val total: Long = 0L,
    val list: List<SportsTailTriggerDto> = emptyList()
)

/**
 * 体育类别 DTO
 */
data class SportsCategoryDto(
    val sport: String = "",
    val image: String? = null,
    val tagId: Long = 0L,
    val name: String = ""
)

/**
 * 体育类别列表响应
 */
data class SportsCategoryListResponse(
    val list: List<SportsCategoryDto> = emptyList()
)

/**
 * 体育市场 DTO
 */
data class SportsMarketDto(
    val conditionId: String = "",
    val question: String = "",
    val outcomes: List<String> = emptyList(),
    val outcomePrices: List<String> = emptyList(),
    val endDate: String? = null,
    val liquidity: String? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val yesTokenId: String? = null,
    val noTokenId: String? = null,
    val eventSlug: String? = null
)

/**
 * 市场搜索请求
 */
data class SportsMarketSearchRequest(
    val sport: String? = null,
    val endDateMin: String? = null,
    val endDateMax: String? = null,
    val minLiquidity: String? = null,
    val keyword: String? = null,
    val limit: Int = 50
)

/**
 * 市场搜索响应
 */
data class SportsMarketSearchResponse(
    val list: List<SportsMarketDto> = emptyList()
)

/**
 * 市场详情请求
 */
data class SportsMarketDetailRequest(
    val conditionId: String = ""
)

/**
 * 市场详情响应
 */
data class SportsMarketDetailResponse(
    val conditionId: String = "",
    val question: String = "",
    val outcomes: List<String> = emptyList(),
    val outcomePrices: List<String> = emptyList(),
    val endDate: String? = null,
    val liquidity: String? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val yesTokenId: String? = null,
    val noTokenId: String? = null,
    val eventSlug: String? = null
)
