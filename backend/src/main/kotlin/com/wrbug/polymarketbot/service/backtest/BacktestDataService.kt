package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 基于 start 游标的一批历史交易结果
 * @param trades 本批交易列表（已按时间升序）
 * @param nextCursorSeconds 下一页游标（API 的 start 参数，秒级）；若本批不足 limit 条则为 null 表示最后一页
 */
data class LeaderTradesBatchResult(
    val trades: List<TradeData>,
    val nextCursorSeconds: Long?
)

/**
 * 回测数据服务
 * 直接从 Polymarket Data API 获取 Leader 历史交易，使用 start 游标分页（避免 offset 过大报错）
 */
@Service
class BacktestDataService(
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(BacktestDataService::class.java)

    /**
     * 按 start 游标获取一批 Leader 历史交易
     * 规则：limit 固定为 500；若返回 500 条则取本批最大时间戳（秒）作为下一页 start，不加 1（同一秒可能多笔订单，由下游按 tradeId 去重）；不足 500 则为最后一页
     *
     * @param leaderId Leader ID
     * @param startTime 回测开始时间（毫秒）
     * @param endTime 回测结束时间（毫秒）
     * @param cursorStartSeconds 本页游标（API 的 start，秒）；首次传 startTime/1000
     * @param limit 每批条数，建议 500
     * @return 本批交易与下一页游标（null 表示没有下一页）
     */
    suspend fun getLeaderHistoricalTradesBatch(
        leaderId: Long,
        startTime: Long,
        endTime: Long,
        cursorStartSeconds: Long,
        limit: Int
    ): LeaderTradesBatchResult {
        logger.info("获取 Leader 历史交易批次: leaderId=$leaderId, cursorStart=$cursorStartSeconds, limit=$limit")

        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: throw IllegalArgumentException("Leader 不存在: $leaderId")

        val dataApi = retrofitFactory.createDataApi()
        val endSeconds = endTime / 1000
        val maxRetries = 5
        val retryDelay = 1000L

        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val response = dataApi.getUserActivity(
                    user = leader.leaderAddress,
                    type = listOf("TRADE"),
                    start = cursorStartSeconds,
                    end = endSeconds,
                    limit = limit,
                    offset = null,
                    sortBy = "TIMESTAMP",
                    sortDirection = "ASC"
                )

                if (!response.isSuccessful || response.body() == null) {
                    throw Exception("从 Data API 获取用户活动失败: code=${response.code()}, message=${response.message()}")
                }

                val activities = response.body()!!
                logger.info("本批获取 ${activities.size} 条活动（第 $attempt 次尝试）")

                val trades = activities.mapNotNull { activity ->
                    try {
                        if (activity.type != "TRADE") return@mapNotNull null
                        if (activity.side == null || activity.price == null || activity.size == null || activity.usdcSize == null) {
                            logger.warn("活动数据缺少必要字段，跳过: activity=$activity")
                            return@mapNotNull null
                        }
                        val tradeTimestamp = activity.timestamp * 1000
                        if (tradeTimestamp < startTime || tradeTimestamp > endTime) {
                            logger.debug("交易时间超出范围，跳过: timestamp=$tradeTimestamp")
                            return@mapNotNull null
                        }
                        TradeData(
                            tradeId = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}_${activity.side}",
                            marketId = activity.conditionId,
                            marketTitle = activity.title,
                            marketSlug = activity.slug,
                            side = activity.side.uppercase(),
                            outcome = activity.outcome ?: activity.outcomeIndex?.toString() ?: "",
                            outcomeIndex = activity.outcomeIndex,
                            price = activity.price.toSafeBigDecimal(),
                            size = activity.size.toSafeBigDecimal(),
                            amount = activity.usdcSize.toSafeBigDecimal(),
                            timestamp = tradeTimestamp
                        )
                    } catch (e: Exception) {
                        logger.warn("转换活动数据失败: activity=$activity, error=${e.message}", e)
                        null
                    }
                }

                // 下一页 start 用本批最大 timestamp（秒），不加 1：同一秒可能有多笔订单，依赖下游按 tradeId 去重
                val nextCursorSeconds: Long? = if (trades.size < limit) {
                    null
                } else {
                    val maxTs = trades.maxOf { it.timestamp }
                    maxTs / 1000
                }
                return LeaderTradesBatchResult(trades = trades, nextCursorSeconds = nextCursorSeconds)
            } catch (e: Exception) {
                lastException = e
                logger.warn("第 $attempt/$maxRetries 次获取批次失败: ${e.message}")
                if (attempt < maxRetries) {
                    logger.info("等待 $retryDelay 毫秒后重试...")
                    delay(retryDelay)
                }
            }
        }
        val errorMsg = "重试 $maxRetries 次后仍然失败，cursorStart=$cursorStartSeconds"
        logger.error(errorMsg, lastException)
        throw Exception(errorMsg, lastException)
    }
}
