package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 回测数据服务
 * 直接从 Polymarket Data API 获取 Leader 历史交易
 */
@Service
class BacktestDataService(
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(BacktestDataService::class.java)

    /**
     * 获取 Leader 历史交易（用于回测）
     *
     * 策略：直接从 Polymarket Data API 的 activity 接口获取
     *
     * @param leaderId Leader ID
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 历史交易列表
     */
    suspend fun getLeaderHistoricalTrades(
        leaderId: Long,
        startTime: Long,
        endTime: Long
    ): List<LeaderTrade> {
        return try {
            logger.info("获取 Leader 历史交易: leaderId=$leaderId, startTime=$startTime, endTime=$endTime")

            // 1. 验证 Leader 是否存在
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: throw IllegalArgumentException("Leader 不存在: $leaderId")

            // 2. 从 Data API 的 activity 接口获取
            val apiTrades = fetchFromActivityApi(leader, startTime, endTime)

            logger.info("共获取 ${apiTrades.size} 条历史交易")
            return apiTrades
        } catch (e: Exception) {
            logger.error("获取 Leader 历史交易失败", e)
            throw e
        }
    }

    /**
     * 从 Data API 的 activity 接口获取历史交易
     * 实现完整的分页逻辑，获取所有历史交易记录
     *
     * @param leader Leader 实体
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 历史交易列表
     */
    private suspend fun fetchFromActivityApi(
        leader: Leader,
        startTime: Long,
        endTime: Long
    ): List<LeaderTrade> {
        logger.info("从 Data API activity 接口获取 Leader 历史交易: leaderId=${leader.id}, timeRange=${startTime} - $endTime")

        val dataApi = retrofitFactory.createDataApi()
        val allTrades = mutableListOf<LeaderTrade>()
        val seenTradeKeys = mutableSetOf<String>()  // 用于内存去重
        var offset = 0
        val pageSize = 100  // 每页最多 100 条
        var hasMore = true
        val MAX_OFFSET = 10000  // 最大偏移量（防止无限循环，15天通常不会超过）

        // 分页获取所有交易记录
        while (hasMore && offset < MAX_OFFSET) {
            try {
                logger.debug("获取第 ${offset / pageSize + 1} 页数据，offset=$offset, limit=$pageSize")

                val response = dataApi.getUserActivity(
                    user = leader.leaderAddress,
                    type = listOf("TRADE"),  // 只获取交易类型
                    start = startTime / 1000,  // Data API 使用秒级时间戳
                    end = endTime / 1000,
                    limit = pageSize,
                    offset = offset,
                    sortBy = "timestamp",
                    sortDirection = "asc"
                )

                if (!response.isSuccessful || response.body() == null) {
                    logger.error("从 Data API 获取用户活动失败: code=${response.code()}, message=${response.message()}")
                    break
                }

                val activities = response.body()!!

                // 如果返回的数据少于 pageSize，说明没有更多数据了
                if (activities.isEmpty() || activities.size < pageSize) {
                    hasMore = false
                }

                // 转换为 LeaderTrade
                val trades = activities.mapNotNull { activity ->
                    try {
                        // 只处理 TRADE 类型
                        if (activity.type != "TRADE") {
                            return@mapNotNull null
                        }

                        // 验证必要字段
                        if (activity.side == null || activity.price == null || activity.size == null || activity.usdcSize == null) {
                            logger.warn("活动数据缺少必要字段，跳过: activity=$activity")
                            return@mapNotNull null
                        }

                        // 验证时间范围（API 可能返回超出范围的数据）
                        val tradeTimestamp = activity.timestamp * 1000  // 转换为毫秒时间戳
                        if (tradeTimestamp < startTime || tradeTimestamp > endTime) {
                            logger.debug("交易时间超出范围，跳过: timestamp=$tradeTimestamp, range=$startTime - $endTime")
                            return@mapNotNull null
                        }

                        // 生成唯一键用于去重（transactionHash + conditionId + timestamp + side）
                        val tradeKey = if (activity.transactionHash != null) {
                            "${activity.transactionHash}_${activity.conditionId}_${activity.timestamp}_${activity.side}"
                        } else {
                            "${activity.timestamp}_${activity.conditionId}_${activity.side}_${activity.price}_${activity.size}"
                        }

                        // 内存去重
                        if (seenTradeKeys.contains(tradeKey)) {
                            logger.debug("发现重复交易，跳过: tradeKey=$tradeKey")
                            return@mapNotNull null
                        }
                        seenTradeKeys.add(tradeKey)

                        LeaderTrade(
                            leaderId = leader.id ?: throw IllegalStateException("Leader ID 不能为空"),
                            tradeId = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}_${activity.side}",  // 使用交易哈希或组合键作为 tradeId
                            marketId = activity.conditionId,  // conditionId 就是市场 ID
                            marketTitle = activity.title,
                            marketSlug = activity.slug,
                            side = activity.side.uppercase(),
                            outcome = activity.outcome ?: activity.outcomeIndex?.toString() ?: "",
                            outcomeIndex = activity.outcomeIndex,
                            price = activity.price.toSafeBigDecimal(),
                            size = activity.size.toSafeBigDecimal(),
                            amount = activity.usdcSize.toSafeBigDecimal(),
                            tradeTimestamp = tradeTimestamp
                        )
                    } catch (e: Exception) {
                        logger.warn("转换活动数据失败: activity=$activity, error=${e.message}", e)
                        null
                    }
                }

                allTrades.addAll(trades)
                logger.debug("已获取 ${trades.size} 条交易，累计 ${allTrades.size} 条")

                // 如果返回的数据少于 pageSize，说明没有更多数据了
                if (activities.size < pageSize) {
                    hasMore = false
                } else {
                    // 继续获取下一页
                    offset += pageSize
                }

                // 防止无限循环（最多获取 MAX_OFFSET 条）
                if (offset >= MAX_OFFSET) {
                    logger.warn("已达到最大分页限制（${MAX_OFFSET} 条），停止获取")
                    break
                }

                // 添加延迟，避免请求过快
                if (hasMore) {
                    delay(200)  // 200ms 延迟
                }

            } catch (e: Exception) {
                logger.error("从 Data API 获取用户活动失败: ${e.message}", e)
                break
            }
        }

        logger.info("分页获取完成，共获取 ${allTrades.size} 条历史交易")
        return allTrades
    }
}

/**
 * Leader 历史交易数据（回测使用）
 */
data class LeaderTrade(
    val leaderId: Long,
    val tradeId: String,  // 交易唯一标识
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val side: String,  // BUY 或 SELL
    val outcome: String?,
    val outcomeIndex: Int?,
    val price: BigDecimal,
    val size: BigDecimal,
    val amount: BigDecimal,  // 交易金额（price × size）
    val tradeTimestamp: Long  // 交易时间戳（毫秒）
)
