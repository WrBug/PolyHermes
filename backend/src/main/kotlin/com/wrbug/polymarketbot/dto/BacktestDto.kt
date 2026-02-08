package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

/**
 * 回测任务创建请求
 */
data class BacktestCreateRequest(
    val taskName: String,  // 回测任务名称
    val leaderId: Long,  // Leader ID
    val initialBalance: String,  // 初始资金
    val backtestDays: Int,  // 回测天数 (1-30)
    // 跟单配置（与 CopyTrading 一致，但不包含 max_position_count）
    val copyMode: String? = null,  // "RATIO" 或 "FIXED"
    val copyRatio: String? = null,  // 仅在 copyMode="RATIO" 时生效
    val fixedAmount: String? = null,  // 仅在 copyMode="FIXED" 时生效
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val supportSell: Boolean? = null,
    val keywordFilterMode: String? = null,  // 关键字过滤模式：DISABLED（不启用）、WHITELIST（白名单）、BLACKLIST（黑名单）
    val keywords: List<String>? = null,  // 关键字列表
    val maxPositionValue: String? = null,  // 最大仓位金额（USDC），NULL表示不启用
    val pageForResume: Int? = null  // 用于恢复中断任务，从指定页码开始获取历史数据（从1开始）
)

/**
 * 回测任务列表请求
 */
data class BacktestListRequest(
    val leaderId: Long? = null,  // Leader ID（可选）
    val status: String? = null,  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED
    val sortBy: String? = null,  // profitAmount / profitRate / createdAt
    val sortOrder: String? = null,  // asc / desc
    val page: Int = 1,  // 页码，从1开始
    val size: Int = 20  // 每页数量
)

/**
 * 回测任务详情请求
 */
data class BacktestDetailRequest(
    val id: Long  // 回测任务ID
)

/**
 * 回测交易记录请求
 */
data class BacktestTradeListRequest(
    val taskId: Long,  // 回测任务ID
    val page: Int = 1,  // 页码，从1开始
    val size: Int = 20  // 每页数量
)

/**
 * 回测进度查询请求
 */
data class BacktestProgressRequest(
    val id: Long  // 回测任务ID
)

/**
 * 回测任务停止请求
 */
data class BacktestStopRequest(
    val id: Long  // 回测任务ID
)

/**
 * 回测任务删除请求
 */
data class BacktestDeleteRequest(
    val id: Long  // 回测任务ID
)

/**
 * 回测任务重试请求
 */
data class BacktestRetryRequest(
    val id: Long  // 回测任务ID
)

/**
 * 按当前配置重新测试请求（仅支持已完成任务）
 */
data class BacktestRerunRequest(
    val id: Long,  // 源回测任务ID
    val taskName: String? = null  // 新任务名称，为空时使用「原名称 (副本)」
)

/**
 * 回测任务列表响应
 */
data class BacktestListResponse(
    val list: List<BacktestTaskDto>,
    val total: Long,
    val page: Int,
    val size: Int
)

/**
 * 回测任务详情响应
 */
data class BacktestDetailResponse(
    val task: BacktestTaskDto,
    val config: BacktestConfigDto,
    val statistics: BacktestStatisticsDto
)

/**
 * 回测交易记录列表响应
 */
data class BacktestTradeListResponse(
    val list: List<BacktestTradeDto>,
    val total: Long,
    val page: Int,
    val size: Int
)

/**
 * 回测进度响应
 */
data class BacktestProgressResponse(
    val progress: Int,  // 执行进度 (0-100)
    val currentBalance: String,  // 当前余额
    val totalTrades: Int,  // 总交易笔数
    val status: String  // 任务状态
)

/**
 * 回测任务 DTO
 */
data class BacktestTaskDto(
    val id: Long,
    val taskName: String,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String?,
    val initialBalance: String,
    val finalBalance: String?,
    val profitAmount: String?,
    val profitRate: String?,
    val backtestDays: Int,
    val startTime: Long,
    val endTime: Long?,
    val status: String,  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED
    val progress: Int,
    val totalTrades: Int,
    val createdAt: Long,
    val executionStartedAt: Long?,
    val executionFinishedAt: Long?
)

/**
 * 回测配置 DTO
 */
data class BacktestConfigDto(
    val copyMode: String,
    val copyRatio: String,
    val fixedAmount: String?,
    val maxOrderSize: String,
    val minOrderSize: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val supportSell: Boolean,
    val keywordFilterMode: String?,
    val keywords: List<String>?,
    val maxPositionValue: String?
)

/**
 * 回测统计信息 DTO
 */
data class BacktestStatisticsDto(
    val totalTrades: Int,  // 总交易笔数
    val buyTrades: Int,  // 买入笔数
    val sellTrades: Int,  // 卖出笔数
    val winTrades: Int,  // 盈利交易笔数
    val lossTrades: Int,  // 亏损交易笔数
    val winRate: String,  // 胜率(%)
    val maxProfit: String,  // 最大单笔盈利
    val maxLoss: String,  // 最大单笔亏损
    val maxDrawdown: String,  // 最大回撤
    val avgHoldingTime: Long?  // 平均持仓时间(毫秒)
)

/**
 * 回测交易记录 DTO
 */
data class BacktestTradeDto(
    val id: Long,
    val tradeTime: Long,
    val marketId: String,
    val marketTitle: String?,
    val side: String,  // BUY/SELL/SETTLEMENT
    val outcome: String,
    val outcomeIndex: Int?,
    val quantity: String,
    val price: String,
    val amount: String,
    val fee: String,
    val profitLoss: String?,
    val balanceAfter: String,
    val leaderTradeId: String?
)

