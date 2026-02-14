package com.wrbug.polymarketbot.service.binance

import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动最小价差：按周期计算。每个周期首次需要时，拉取该周期前的 20 根已收盘 K 线，按方向筛选、IQR 剔除后求平均，缓存 100% 基准值 (interval, period)。
 * 触发时由调用方按窗口进度计算动态系数（100%→50%）后得到有效最小价差。不在保存策略时计算。
 */
@Service
class BinanceKlineAutoSpreadService(
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(BinanceKlineAutoSpreadService::class.java)

    private val symbol = "BTCUSDC"
    private val historyLimit = 20
    private val minSamplesAfterIqr = 3

    /** (intervalSeconds, periodStartUnix) -> (baseSpreadUp, baseSpreadDown)，100% 基准价差 */
    private val cache = ConcurrentHashMap<String, Pair<BigDecimal, BigDecimal>>()

    private fun cacheKey(intervalSeconds: Int, periodStartUnix: Long): String = "$intervalSeconds-$periodStartUnix"

    /** 返回该周期、该方向的 100% 基准价差，供调用方按窗口进度应用动态系数。 */
    fun getAutoMinSpreadBase(intervalSeconds: Int, periodStartUnix: Long, outcomeIndex: Int): BigDecimal? {
        val key = cacheKey(intervalSeconds, periodStartUnix)
        val (up, down) = cache[key] ?: run {
            computeAndCache(intervalSeconds, periodStartUnix) ?: return null
        }
        return if (outcomeIndex == 0) up else down
    }

    /** 计算并缓存 100% 基准价差（IQR 平均，不乘系数）。预加载与触发时共用此缓存。 */
    fun computeAndCache(intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val intervalStr = if (intervalSeconds == 300) "5m" else "15m"
        val endTimeMs = periodStartUnix * 1000L
        val klines = fetchKlines(intervalStr, historyLimit, endTime = endTimeMs) ?: return null
        val spreadsUp = mutableListOf<BigDecimal>()
        val spreadsDown = mutableListOf<BigDecimal>()
        for (k in klines) {
            if (k.size < 5) continue
            val openP = k.getOrNull(1)?.toString()?.toSafeBigDecimal() ?: continue
            val closeP = k.getOrNull(4)?.toString()?.toSafeBigDecimal() ?: continue
            if (closeP > openP) spreadsUp.add(closeP.subtract(openP))
            if (closeP < openP) spreadsDown.add(openP.subtract(closeP))
        }
        val baseUp = averageAfterIqr(spreadsUp).setScale(8, RoundingMode.HALF_UP)
        val baseDown = averageAfterIqr(spreadsDown).setScale(8, RoundingMode.HALF_UP)
        cache[cacheKey(intervalSeconds, periodStartUnix)] = baseUp to baseDown
        logger.info(
            "尾盘自动价差已计算并缓存(100%基准): interval=${intervalSeconds}s periodStartUnix=$periodStartUnix | " +
                "Up方向: 样本数=${spreadsUp.size}, baseSpreadUp=${baseUp.toPlainString()} | " +
                "Down方向: 样本数=${spreadsDown.size}, baseSpreadDown=${baseDown.toPlainString()}"
        )
        return baseUp to baseDown
    }

    private fun fetchKlines(interval: String, limit: Int, endTime: Long? = null): List<List<Any>>? {
        return try {
            val api = retrofitFactory.createBinanceApi()
            val call = api.getKlines(symbol = symbol, interval = interval, limit = limit, endTime = endTime)
            val response = call.execute()
            if (response.isSuccessful && response.body() != null) response.body() else null
        } catch (e: Exception) {
            logger.warn("拉取币安 K 线失败: ${e.message}")
            null
        }
    }

    /**
     * IQR 剔除异常值后求平均；若剔除后样本数 < minSamplesAfterIqr 则不剔除，用全量求平均。
     */
    private fun averageAfterIqr(list: List<BigDecimal>): BigDecimal {
        if (list.isEmpty()) return BigDecimal.ZERO
        val sorted = list.sorted()
        val n = sorted.size
        val q1Idx = (n * 0.25).toInt().coerceIn(0, n - 1)
        val q3Idx = (n * 0.75).toInt().coerceIn(0, n - 1)
        val q1 = sorted[q1Idx]
        val q3 = sorted[q3Idx]
        val iqr = q3.subtract(q1)
        val lower = q1.subtract(iqr.multiply(BigDecimal("1.5")))
        val upper = q3.add(iqr.multiply(BigDecimal("1.5")))
        val filtered = sorted.filter { it >= lower && it <= upper }
        val use = if (filtered.size < minSamplesAfterIqr) sorted else filtered
        return use.fold(BigDecimal.ZERO) { a, b -> a.add(b) }.divide(BigDecimal(use.size), 18, RoundingMode.HALF_UP)
    }
}
