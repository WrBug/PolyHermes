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
import org.springframework.dao.DataIntegrityViolationException
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

    /** 体育联赛列表缓存（tagId → seriesId 解析） */
    private val sportsCategoriesCache: Cache<String, List<SportCategoryResult>> = Caffeine.newBuilder()
        .maximumSize(1)
        .expireAfterWrite(java.time.Duration.ofMinutes(10))
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
            
            try {
                val savedMarket = marketRepository.save(market)
                marketCache.put(marketId, savedMarket)
                savedMarket
            } catch (e: DataIntegrityViolationException) {
                // 并发写入同一个 marketId 时可能触发唯一索引冲突，这里降级为查询并返回已有记录
                val existingAfter = marketRepository.findByMarketId(marketId)
                if (existingAfter != null) {
                    marketCache.put(marketId, existingAfter)
                    existingAfter
                } else {
                    throw e
                }
            }
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

    /**
     * 搜索市场（按标题关键词，可选按标签筛选）
     * 调用 Gamma API /markets?title=xxx 搜索，返回活跃且未关闭的市场
     */
    suspend fun searchMarkets(
        keyword: String,
        tagId: String? = null,
        seriesId: String? = null,
        sportSlug: String? = null,
        limit: Int = 20
    ): List<MarketSearchResult> {
        if (keyword.isBlank() && tagId.isNullOrBlank() && seriesId.isNullOrBlank() && sportSlug.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val sport = resolveSportCategory(seriesId, tagId, sportSlug)
            if (sport != null && !sport.seriesId.isNullOrBlank()) {
                searchSportsLeagueMarkets(keyword, sport, limit)
            } else {
                searchMarketsByTag(keyword, tagId, limit)
            }
        } catch (e: Exception) {
            logger.warn("搜索市场失败: keyword=$keyword, seriesId=$seriesId, sportSlug=$sportSlug, tagId=$tagId, error=${e.message}")
            emptyList()
        }
    }

    /**
     * 解析体育联赛配置（seriesId 用于单场比赛，tagId 用于长期市场）
     */
    private suspend fun resolveSportCategory(
        seriesId: String?,
        tagId: String?,
        sportSlug: String?
    ): SportCategoryResult? {
        val sports = getSportsCategoriesCached()
        seriesId?.takeIf { it.isNotBlank() }?.let { sid ->
            sports.find { it.seriesId == sid }?.let { return it }
        }
        sportSlug?.trim()?.takeIf { it.isNotBlank() }?.let { slug ->
            sports.find { it.slug.equals(slug, ignoreCase = true) }?.let { return it }
        }
        tagId?.takeIf { it.isNotBlank() }?.let { tid ->
            sports.find { it.tagId == tid }?.let { return it }
        }
        return null
    }

    /**
     * 体育联赛：单场比赛（series events）+ 长期市场（tag markets）合并返回
     */
    private suspend fun searchSportsLeagueMarkets(
        keyword: String,
        sport: SportCategoryResult,
        limit: Int
    ): List<MarketSearchResult> {
        val seriesId = sport.seriesId ?: return searchMarketsByTag(keyword, sport.tagId, limit)
        val gameLimit = ((limit * 2) / 3).coerceIn(50, limit)
        val seasonLimit = (limit - gameLimit).coerceAtLeast(30)
        val games = searchMarketsBySeries(keyword, seriesId, gameLimit)
        val seenIds = games.map { it.conditionId }.toMutableSet()
        val seasons = searchMarketsByTag(keyword, sport.tagId, seasonLimit)
            .filter { seenIds.add(it.conditionId) }
        return games + seasons
    }

    private suspend fun getSportsCategoriesCached(): List<SportCategoryResult> {
        sportsCategoriesCache.getIfPresent("all")?.let { return it }
        val fresh = fetchSportsCategoriesFromApi()
        sportsCategoriesCache.put("all", fresh)
        return fresh
    }

    private suspend fun searchMarketsByTag(keyword: String, tagId: String?, limit: Int): List<MarketSearchResult> {
        val gammaApi = retrofitFactory.createGammaApi()
        val response = gammaApi.listMarkets(
            title = keyword,
            tagId = tagId,
            closed = false,
            active = true,
            limit = limit
        )
        if (!response.isSuccessful || response.body().isNullOrEmpty()) return emptyList()
        return response.body()!!.mapNotNull { m ->
            m.toMarketSearchResult(marketType = MarketSearchResult.TYPE_SEASON)
        }
    }

    /**
     * 体育联赛：通过 series_id 拉取 events，展开其中活跃 markets（单场比赛、让分等）
     */
    private suspend fun searchMarketsBySeries(keyword: String, seriesId: String, limit: Int): List<MarketSearchResult> {
        val gammaApi = retrofitFactory.createGammaApi()
        val kw = keyword.trim().lowercase()
        val results = mutableListOf<MarketSearchResult>()
        val seenConditionIds = mutableSetOf<String>()
        var offset = 0
        val pageSize = 30
        while (results.size < limit && offset < 300) {
            val response = gammaApi.listEvents(
                seriesId = seriesId,
                closed = false,
                active = true,
                limit = pageSize,
                offset = offset,
                order = "volume",
                ascending = false
            )
            if (!response.isSuccessful || response.body().isNullOrEmpty()) break
            val events = response.body()!!
            if (events.isEmpty()) break
            for (event in events) {
                val eventTitle = event.title?.trim().orEmpty()
                for (market in event.markets.orEmpty()) {
                    if (market.active != true || market.closed == true) continue
                    val item = market.toMarketSearchResult(
                        eventTitle = eventTitle,
                        eventSlug = event.slug,
                        eventCategory = event.category,
                        eventImage = event.image,
                        eventIcon = event.icon,
                        marketType = MarketSearchResult.TYPE_GAME
                    ) ?: continue
                    if (!seenConditionIds.add(item.conditionId)) continue
                    if (kw.length >= 2) {
                        val haystack = "${eventTitle} ${item.title}".lowercase()
                        if (!haystack.contains(kw)) continue
                    }
                    results.add(item)
                    if (results.size >= limit) return results
                }
            }
            offset += pageSize
            if (events.size < pageSize) break
        }
        return results
    }

    private fun pickImageUrl(image: String?, icon: String?): String? {
        return image?.trim()?.takeIf { it.isNotBlank() }
            ?: icon?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun MarketResponse.toMarketSearchResult(
        eventTitle: String = "",
        eventSlug: String? = null,
        eventCategory: String? = null,
        eventImage: String? = null,
        eventIcon: String? = null,
        marketType: String = MarketSearchResult.TYPE_SEASON
    ): MarketSearchResult? {
        val conditionId = conditionId ?: return null
        val question = question?.trim().orEmpty()
        val displayEventTitle = if (marketType == MarketSearchResult.TYPE_GAME) {
            eventTitle.takeIf { it.isNotBlank() && !question.contains(it, ignoreCase = true) }
        } else {
            null
        }
        val marketImageUrl = pickImageUrl(image, icon)
        val eventImageUrl = if (marketType == MarketSearchResult.TYPE_GAME) {
            pickImageUrl(eventImage, eventIcon) ?: marketImageUrl
        } else {
            null
        }
        return MarketSearchResult(
            conditionId = conditionId,
            title = question,
            slug = slug ?: eventSlug,
            category = category ?: eventCategory,
            volume = volume,
            outcomes = outcomes,
            eventTitle = displayEventTitle,
            marketType = marketType,
            image = marketImageUrl,
            icon = icon?.trim()?.takeIf { it.isNotBlank() },
            eventImage = eventImageUrl
        )
    }

    private val sportNameMap = mapOf(
        "ncaab" to "NCAA Basketball", "epl" to "EPL", "lal" to "La Liga",
        "ipl" to "IPL Cricket", "wnba" to "WNBA", "bun" to "Bundesliga",
        "mlb" to "MLB", "cfb" to "CFB", "nfl" to "NFL",
        "fl1" to "Ligue 1", "sea" to "Serie A", "ucl" to "Champions League",
        "afc" to "AFC", "ofc" to "OFC", "acn" to "Africa Cup of Nations",
        "ncaaw" to "NCAA Women's BB", "clp" to "Copa Libertadores",
        "mls" to "MLS", "nba" to "NBA", "nhl" to "NHL"
    )

    private val popularSportSlugs = listOf("nba", "nfl", "mlb", "nhl", "epl", "ucl", "lal", "serie-a", "sea", "bun")

    /**
     * 获取体育联赛子分类列表（仅含具备 seriesId 的联赛，用于拉取单场比赛盘口）
     */
    suspend fun listSportsCategories(): List<SportCategoryResult> {
        return try {
            getSportsCategoriesCached()
        } catch (e: Exception) {
            logger.warn("获取体育分类失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchSportsCategoriesFromApi(): List<SportCategoryResult> {
        val gammaApi = retrofitFactory.createGammaApi()
        val response = gammaApi.listSports()
        if (!response.isSuccessful || response.body().isNullOrEmpty()) return emptyList()
        return response.body()!!.mapNotNull { sport ->
            val slug = sport.sport ?: return@mapNotNull null
            val series = sport.series?.trim().orEmpty()
            if (series.isEmpty()) return@mapNotNull null
            val tags = (sport.tags ?: "").split(",")
            val specificTag = tags.firstOrNull { it != "1" && it != "100639" } ?: return@mapNotNull null
            SportCategoryResult(
                id = sport.id ?: return@mapNotNull null,
                slug = slug,
                label = sportNameMap[slug] ?: slug.uppercase(),
                tagId = specificTag,
                seriesId = series,
                image = sport.image
            )
        }.sortedBy { sport ->
            val idx = popularSportSlugs.indexOf(sport.slug)
            if (idx >= 0) idx else Int.MAX_VALUE
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

data class MarketSearchResult(
    val conditionId: String,
    val title: String,
    val slug: String? = null,
    val category: String? = null,
    val volume: String? = null,
    val outcomes: String? = null,
    /** 所属赛事/对阵（体育单场比赛） */
    val eventTitle: String? = null,
    /** game=单场赛事盘口，season=长期/赛季类市场 */
    val marketType: String = TYPE_SEASON,
    /** 市场封面图（优先 image，无则 icon） */
    val image: String? = null,
    val icon: String? = null,
    /** 所属赛事封面（体育单场比赛分组用） */
    val eventImage: String? = null
) {
    companion object {
        const val TYPE_GAME = "game"
        const val TYPE_SEASON = "season"
    }
}

data class SportCategoryResult(
    val id: Int,
    val slug: String,
    val label: String,
    val tagId: String,
    val seriesId: String? = null,
    val image: String? = null
)
