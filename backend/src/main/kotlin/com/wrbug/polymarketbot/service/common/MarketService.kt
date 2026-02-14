package com.wrbug.polymarketbot.service.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.getEventSlug
import com.wrbug.polymarketbot.util.parseStringArray
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 市场信息服务
 * 负责缓存和管理市场信息
 */
@Service
class MarketService(
    val marketRepository: MarketRepository,  // 改为 public，供 MarketPollingService 使用
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(MarketService::class.java)

    // LRU 缓存（避免频繁查询数据库），最多缓存 200 条记录
    private val marketCache: Cache<String, Market> = Caffeine.newBuilder()
        .maximumSize(200)  // 最多缓存 200 条记录
        .build()
    
    /**
     * 根据市场ID获取市场信息
     * 优先从缓存获取，如果不存在则从数据库查询，如果数据库也没有则从API获取并保存
     */
    fun getMarket(marketId: String): Market? {
        // 1. 从缓存获取
        marketCache.getIfPresent(marketId)?.let { return it }

        // 2. 从数据库查询
        val market = marketRepository.findByMarketId(marketId)
        if (market != null) {
            marketCache.put(marketId, market)
            return market
        }

        // 3. 从API获取（异步，不阻塞）
        runBlocking {
            try {
                fetchAndSaveMarket(marketId)
            } catch (e: Exception) {
                logger.warn("获取市场信息失败: marketId=$marketId, error=${e.message}")
            }
        }

        // 再次从数据库查询（API可能已经保存）
        return marketRepository.findByMarketId(marketId)?.also {
            marketCache.put(marketId, it)
        }
    }
    
    /**
     * 批量获取市场信息
     */
    fun getMarkets(marketIds: List<String>): Map<String, Market> {
        val result = mutableMapOf<String, Market>()
        val missingIds = mutableListOf<String>()
        
        // 1. 从缓存和数据库获取
        for (marketId in marketIds) {
            val market = getMarket(marketId)
            if (market != null) {
                result[marketId] = market
            } else {
                missingIds.add(marketId)
            }
        }
        
        // 2. 批量从API获取缺失的市场信息
        if (missingIds.isNotEmpty()) {
            runBlocking {
                try {
                    fetchAndSaveMarkets(missingIds)
                } catch (e: Exception) {
                    logger.warn("批量获取市场信息失败: marketIds=$missingIds, error=${e.message}")
                }
            }
            
            // 再次从数据库查询
            val savedMarkets = marketRepository.findByMarketIdIn(missingIds)
            for (market in savedMarkets) {
                result[market.marketId] = market
                marketCache.put(market.marketId, market)
            }
        }
        
        return result
    }
    
    /**
     * 从API获取市场信息并保存到数据库
     */
    private suspend fun fetchAndSaveMarket(marketId: String): Market? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(marketId))
            
            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                if (markets.isNotEmpty()) {
                    val marketResponse = markets.first()
                    saveMarketFromResponse(marketId, marketResponse)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("从API获取市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    /**
     * 批量从API获取市场信息并保存到数据库
     */
    private suspend fun fetchAndSaveMarkets(marketIds: List<String>) {
        if (marketIds.isEmpty()) return
        
        try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = marketIds)
            
            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val marketMap = markets.associateBy { it.conditionId ?: "" }
                
                for (marketId in marketIds) {
                    val marketResponse = marketMap[marketId]
                    if (marketResponse != null) {
                        saveMarketFromResponse(marketId, marketResponse)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("批量从API获取市场信息失败: marketIds=$marketIds, error=${e.message}", e)
        }
    }
    
    /**
     * 从API响应保存市场信息到数据库
     */
    private fun saveMarketFromResponse(marketId: String, marketResponse: MarketResponse): Market? {
        return try {
            val existingMarket = marketRepository.findByMarketId(marketId)
            
            // 保存原来的 slug（用于显示）
            val slug = marketResponse.slug
            // 保存跳转用的 slug（从 events[0].slug 获取）
            val eventSlug = marketResponse.getEventSlug()
            
            val market = if (existingMarket != null) {
                // 更新现有市场信息
                existingMarket.copy(
                    title = marketResponse.question ?: existingMarket.title,
                    slug = slug ?: existingMarket.slug,
                    eventSlug = eventSlug ?: existingMarket.eventSlug,
                    category = marketResponse.category ?: existingMarket.category,
                    icon = marketResponse.icon ?: existingMarket.icon,
                    image = marketResponse.image ?: existingMarket.image,
                    description = marketResponse.description ?: existingMarket.description,
                    active = marketResponse.active ?: existingMarket.active,
                    closed = marketResponse.closed ?: existingMarket.closed,
                    archived = marketResponse.archived ?: existingMarket.archived,
                    endDate = parseEndDate(marketResponse.endDate),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                // 创建新市场信息
                Market(
                    marketId = marketId,
                    title = marketResponse.question ?: marketId,
                    slug = slug,
                    eventSlug = eventSlug,
                    category = marketResponse.category,
                    icon = marketResponse.icon,
                    image = marketResponse.image,
                    description = marketResponse.description,
                    active = marketResponse.active ?: true,
                    closed = marketResponse.closed ?: false,
                    archived = marketResponse.archived ?: false,
                    endDate = parseEndDate(marketResponse.endDate),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val savedMarket = marketRepository.save(market)
            marketCache.put(marketId, savedMarket)
            savedMarket
        } catch (e: Exception) {
            logger.error("保存市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    /**
     * 按 tokenId 从 Gamma 解析市场信息（conditionId、outcomeIndex）
     * 用于链上解析时 Gamma 失败、仅带 tokenId 的交易在 processBuyTrade 中补查市场
     */
    suspend fun getMarketInfoByTokenId(tokenId: String): MarketInfoByTokenId? {
        if (tokenId.isBlank()) return null
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(
                conditionIds = null,
                clobTokenIds = listOf(tokenId),
                includeTag = null
            )
            if (!response.isSuccessful || response.body().isNullOrEmpty()) return null
            val market = response.body()!!.first()
            val conditionId = market.conditionId ?: return null
            val clobTokenIdsRaw = market.clobTokenIds ?: market.clob_token_ids
            val clobTokenIds = (clobTokenIdsRaw ?: "").parseStringArray()
            val outcomeIndex = clobTokenIds.indexOfFirst { it.equals(tokenId, ignoreCase = true) }.takeIf { it >= 0 }
                ?: return null
            val outcomes = market.outcomes.parseStringArray()
            val outcome = if (outcomeIndex < outcomes.size) outcomes[outcomeIndex] else null
            saveMarketFromResponse(conditionId, market)
            MarketInfoByTokenId(conditionId = conditionId, outcomeIndex = outcomeIndex, outcome = outcome)
        } catch (e: Exception) {
            logger.warn("按 tokenId 查询市场失败: tokenId=$tokenId, error=${e.message}")
            null
        }
    }

    /**
     * 清除缓存（用于测试或手动刷新）
     */
    fun clearCache() {
        marketCache.invalidateAll()
    }
    
    /**
     * 解析市场截止时间（ISO 8601 格式）
     */
    private fun parseEndDate(endDate: String?): Long? {
        if (endDate.isNullOrBlank()) {
            return null
        }
        
        return try {
            // ISO 8601 格式，例如：2025-03-15T12:00:00Z
            Instant.parse(endDate).toEpochMilli()
        } catch (e: Exception) {
            logger.warn("解析市场截止时间失败: endDate=$endDate, error=${e.message}")
            null
        }
    }

    /**
     * 根据 conditionId 查询该市场是否为 Neg Risk（需使用 Neg Risk Exchange 签约）
     * 用于跟单下单时选择正确的 exchange 合约，避免 invalid signature
     */
    suspend fun getNegRiskByConditionId(conditionId: String): Boolean? {
        if (conditionId.isBlank()) return null
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(conditionId))
            if (!response.isSuccessful || response.body().isNullOrEmpty()) return null
            val marketResponse = response.body()!!.first()
            val fromEvent = marketResponse.events?.firstOrNull()?.negRisk
            val fromMarket = marketResponse.negRisk ?: marketResponse.negRiskOther
            fromEvent ?: fromMarket
        } catch (e: Exception) {
            logger.warn("查询市场 negRisk 失败: conditionId=$conditionId, error=${e.message}")
            null
        }
    }
}

/**
 * 按 tokenId 查询 Gamma 得到的市场信息（用于补全 trade.market / outcomeIndex）
 */
data class MarketInfoByTokenId(
    val conditionId: String,
    val outcomeIndex: Int,
    val outcome: String? = null
)
