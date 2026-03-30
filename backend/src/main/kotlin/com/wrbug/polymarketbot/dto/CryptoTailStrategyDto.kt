package com.wrbug.polymarketbot.dto

/**
 * 加密价差策略创建请求
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
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String = "NONE",
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差, MAX=最大价差 */
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true
)

/**
 * 加密价差策略更新请求
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
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String? = null,
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差, MAX=最大价差 */
    val spreadDirection: String? = null,
    val enabled: Boolean? = null
)

/**
 * 加密价差策略列表请求
 */
data class CryptoTailStrategyListRequest(
    val accountId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 加密价差策略 DTO（列表与详情）
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
    /** 价差模式: NONE, FIXED, AUTO */
    val spreadMode: String = "NONE",
    /** 价差数值 */
    val spreadValue: String? = null,
    /** 价差方向: MIN=最小价差（价差>=配置值触发）, MAX=最大价差（价差<=配置值触发） */
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true,
    val lastTriggerAt: Long? = null,
    /** 已实现总收益 USDC（已结算订单的 realizedPnl 之和） */
    val totalRealizedPnl: String? = null,
    /** 已结算笔数（用于胜率分母） */
    val settledCount: Long = 0L,
    /** 已结算中赢的笔数（用于胜率分子） */
    val winCount: Long = 0L,
    /** 胜率 0~1（已结算时 = winCount/settledCount，无结算为 null） */
    val winRate: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/**
 * 加密价差策略列表响应
 */
data class CryptoTailStrategyListResponse(
    val list: List<CryptoTailStrategyDto> = emptyList()
)

/**
 * 加密价差策略删除请求
 */
data class CryptoTailStrategyDeleteRequest(
    val strategyId: Long = 0L
)

/**
 * 触发记录列表请求
 * @param startDate 开始日期（当天 00:00:00.000 的时间戳毫秒），为 null 表示不限制
 * @param endDate 结束日期（当天 23:59:59.999 的时间戳毫秒），为 null 表示不限制
 */
data class CryptoTailStrategyTriggerListRequest(
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
    /** 是否已结算 */
    val resolved: Boolean = false,
    /** 已实现盈亏 USDC（结算后有值） */
    val realizedPnl: String? = null,
    /** 市场赢家 outcome 索引（结算后有值） */
    val winnerOutcomeIndex: Int? = null,
    val settledAt: Long? = null,
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
 * 自动价差计算响应（按 30 根历史 K 线 + IQR 剔除后 × 0.7）
 */
data class CryptoTailAutoMinSpreadResponse(
    val minSpreadUp: String = "0",
    val minSpreadDown: String = "0"
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

/**
 * 收益曲线请求
 * @param strategyId 策略ID
 * @param startDate 开始时间（毫秒时间戳），null 表示不限制
 * @param endDate 结束时间（毫秒时间戳），null 表示不限制
 */
data class CryptoTailPnlCurveRequest(
    val strategyId: Long = 0L,
    val startDate: Long? = null,
    val endDate: Long? = null
)

/**
 * 收益曲线单点数据
 */
data class CryptoTailPnlCurvePoint(
    /** 时间点（毫秒时间戳，结算时间或创建时间） */
    val timestamp: Long = 0L,
    /** 累计收益 USDC */
    val cumulativePnl: String = "0",
    /** 当笔收益 USDC */
    val pointPnl: String = "0",
    /** 截至该点累计已结算笔数 */
    val settledCount: Long = 0L
)

/**
 * 收益曲线响应
 */
data class CryptoTailPnlCurveResponse(
    val strategyId: Long = 0L,
    val strategyName: String = "",
    /** 筛选范围内总已实现收益 USDC */
    val totalRealizedPnl: String = "0",
    val settledCount: Long = 0L,
    val winCount: Long = 0L,
    val winRate: String? = null,
    /** 最大回撤 USDC（正数表示回撤幅度） */
    val maxDrawdown: String? = null,
    val curveData: List<CryptoTailPnlCurvePoint> = emptyList()
)
