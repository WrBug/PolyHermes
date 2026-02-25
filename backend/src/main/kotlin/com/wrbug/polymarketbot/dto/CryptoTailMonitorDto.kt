package com.wrbug.polymarketbot.dto

/**
 * 尾盘策略监控初始化请求
 */
data class CryptoTailMonitorInitRequest(
    /** 策略ID */
    val strategyId: Long = 0L
)

/**
 * 尾盘策略监控初始化响应
 */
data class CryptoTailMonitorInitResponse(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 策略名称 */
    val name: String = "",
    /** 账户ID */
    val accountId: Long = 0L,
    /** 账户名称 */
    val accountName: String = "",
    /** 市场 slug 前缀 */
    val marketSlugPrefix: String = "",
    /** 市场标题 */
    val marketTitle: String = "",
    /** 周期秒数 (300=5m, 900=15m) */
    val intervalSeconds: Int = 300,
    /** 当前周期开始时间 (Unix 秒) */
    val periodStartUnix: Long = 0L,
    /** 时间窗口开始秒数 */
    val windowStartSeconds: Int = 0,
    /** 时间窗口结束秒数 */
    val windowEndSeconds: Int = 0,
    /** 最低价格 */
    val minPrice: String = "0",
    /** 最高价格 */
    val maxPrice: String = "1",
    /** 最小价差模式: NONE, FIXED, AUTO */
    val minSpreadMode: String = "NONE",
    /** 价差方向: MIN（显示周期内最小价差）, MAX（显示周期内最大价差） */
    val spreadDirection: String = "MIN",
    /** 最小价差数值 (FIXED 时有值) */
    val minSpreadValue: String? = null,
    /** 自动计算的最小价差 (Up方向) */
    val autoMinSpreadUp: String? = null,
    /** 自动计算的最小价差 (Down方向) */
    val autoMinSpreadDown: String? = null,
    /** BTC 开盘价 USDC（来自币安 K 线 open） */
    val openPriceBtc: String? = null,
    /** Up tokenId */
    val tokenIdUp: String? = null,
    /** Down tokenId */
    val tokenIdDown: String? = null,
    /** 当前时间 (毫秒时间戳) */
    val currentTimestamp: Long = System.currentTimeMillis(),
    /** 是否启用 */
    val enabled: Boolean = true
)

/**
 * 尾盘策略监控实时推送数据
 */
data class CryptoTailMonitorPushData(
    /** 策略ID */
    val strategyId: Long = 0L,
    /** 推送时间 (毫秒时间戳) */
    val timestamp: Long = System.currentTimeMillis(),
    /** 当前周期开始时间 (Unix 秒) */
    val periodStartUnix: Long = 0L,
    /** 当前价格 (Up方向，来自订单簿) */
    val currentPriceUp: String? = null,
    /** 当前价格 (Down方向，来自订单簿) */
    val currentPriceDown: String? = null,
    /** 当前价差 (Up方向: 1 - currentPriceUp) */
    val spreadUp: String? = null,
    /** 当前价差 (Down方向: currentPriceUp) */
    val spreadDown: String? = null,
    /** 最小价差线 (Up方向) */
    val minSpreadLineUp: String? = null,
    /** 最小价差线 (Down方向，USDC 价差) */
    val minSpreadLineDown: String? = null,
    /** BTC 开盘价 USDC（币安 K 线 open） */
    val openPriceBtc: String? = null,
    /** BTC 最新价 USDC（币安 K 线 close，当前周期实时） */
    val currentPriceBtc: String? = null,
    /** BTC 价差 USDC（currentPriceBtc - openPriceBtc） */
    val spreadBtc: String? = null,
    /** 周期剩余秒数 */
    val remainingSeconds: Int = 0,
    /** 是否在时间窗口内 */
    val inTimeWindow: Boolean = false,
    /** 是否在价格区间内 (Up方向) */
    val inPriceRangeUp: Boolean = false,
    /** 是否在价格区间内 (Down方向) */
    val inPriceRangeDown: Boolean = false,
    /** 是否已触发 */
    val triggered: Boolean = false,
    /** 触发方向: UP, DOWN, null */
    val triggerDirection: String? = null,
    /** 周期是否已结束 */
    val periodEnded: Boolean = false
)
