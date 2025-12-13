package com.wrbug.polymarketbot.service.nba

import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.dto.NbaMarketDto
import com.wrbug.polymarketbot.dto.NbaMarketListRequest
import com.wrbug.polymarketbot.dto.NbaMarketListResponse
import com.wrbug.polymarketbot.enums.SportsTagId
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * NBA 市场服务
 * 用于从 Polymarket 获取 NBA 相关的市场信息
 */
@Service
class NbaMarketService(
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(NbaMarketService::class.java)
    
    /**
     * 获取 NBA 的 tag ID 列表
     * 直接使用枚举中定义的已知 tag ID，无需调用 API
     */
    suspend fun getNbaTagIds(): Result<List<String>> {
        // 直接使用枚举中定义的 NBA tag ID
        val nbaTagId = SportsTagId.NBA.tagId
        logger.debug("使用枚举中的 NBA tag ID: $nbaTagId")
        return Result.success(listOf(nbaTagId))
    }
    
    /**
     * 获取 NBA 市场列表
     * 使用 NBA 的 tag IDs 过滤市场
     * 
     * @param request 请求参数
     * @return NBA 市场列表响应
     */
    suspend fun getNbaMarkets(request: NbaMarketListRequest): Result<NbaMarketListResponse> {
        return try {
            // 先获取 NBA 的 tag IDs
            val tagIdsResult = getNbaTagIds()
            if (tagIdsResult.isFailure) {
                return Result.failure(tagIdsResult.exceptionOrNull() ?: Exception("无法获取 NBA tag IDs"))
            }
            
            val tagIds = tagIdsResult.getOrNull() ?: return Result.failure(IllegalStateException("NBA tag IDs 为空"))
            
            if (tagIds.isEmpty()) {
                logger.warn("NBA tag IDs 为空，无法过滤市场")
                return Result.success(NbaMarketListResponse(
                    list = emptyList(),
                    total = 0L
                ))
            }
            
            // 调用 /markets 接口，使用 tag IDs 过滤
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(
                conditionIds = null,
                includeTag = true,
                tags = tagIds,
                active = request.active,
                closed = request.closed,
                archived = request.archived
            )
            
            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                logger.info("获取到 ${markets.size} 个 NBA 市场")
                
                // 转换为 DTO
                val marketDtos = markets.map { market ->
                    NbaMarketDto(
                        id = market.id,
                        question = market.question,
                        conditionId = market.conditionId,
                        slug = market.slug,
                        description = market.description,
                        category = market.category,
                        active = market.active,
                        closed = market.closed,
                        archived = market.archived,
                        volume = market.volume,
                        liquidity = market.liquidity,
                        endDate = market.endDate,
                        startDate = market.startDate,
                        outcomes = market.outcomes,
                        outcomePrices = market.outcomePrices,
                        volumeNum = market.volumeNum,
                        liquidityNum = market.liquidityNum,
                        lastTradePrice = market.lastTradePrice,
                        bestBid = market.bestBid,
                        bestAsk = market.bestAsk
                    )
                }
                
                Result.success(NbaMarketListResponse(
                    list = marketDtos,
                    total = marketDtos.size.toLong()
                ))
            } else {
                logger.error("获取 NBA 市场失败: ${response.code()} ${response.message()}")
                val errorBody = response.errorBody()?.string()
                logger.error("错误响应体: $errorBody")
                Result.failure(Exception("获取 NBA 市场失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取 NBA 市场异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从 NBA 市场中提取球队列表
     * 解析市场名称，提取所有唯一的球队名称
     * 
     * @param active 是否只从活跃市场提取（默认 true）
     * @return 球队名称列表（去重、排序）
     */
    suspend fun getTeamsFromMarkets(active: Boolean = true): Result<List<String>> {
        return try {
            // 获取 NBA 市场列表
            val marketsResult = getNbaMarkets(
                NbaMarketListRequest(
                    active = active,
                    closed = false,
                    archived = false
                )
            )
            
            if (marketsResult.isFailure) {
                return Result.failure(marketsResult.exceptionOrNull() ?: Exception("无法获取 NBA 市场"))
            }
            
            val markets = marketsResult.getOrNull()?.list ?: return Result.success(emptyList())
            
            // 使用市场名称解析器提取球队
            val teams = mutableSetOf<String>()
            
            markets.forEach { market ->
                if (!market.question.isNullOrBlank()) {
                    val parsed = NbaMarketNameParser.parse(market.question)
                    if (parsed != null) {
                        // 提取主队和客队
                        parsed.homeTeam?.let { teams.add(it) }
                        parsed.awayTeam?.let { teams.add(it) }
                    }
                }
            }
            
            // 排序并返回
            val sortedTeams = teams.sorted()
            logger.info("从 ${markets.size} 个市场中提取到 ${sortedTeams.size} 个球队")
            Result.success(sortedTeams)
        } catch (e: Exception) {
            logger.error("从市场提取球队列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
}

