package com.wrbug.polymarketbot.service.sportstail

import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.SportsTailStrategy
import com.wrbug.polymarketbot.entity.SportsTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.event.SportsTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.SportsTailStrategyRepository
import com.wrbug.polymarketbot.repository.SportsTailStrategyTriggerRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class SportsTailStrategyService(
    private val strategyRepository: SportsTailStrategyRepository,
    private val triggerRepository: SportsTailStrategyTriggerRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(SportsTailStrategyService::class.java)

    companion object {
        private val SPORT_NAMES = mapOf(
            "nba" to "NBA",
            "nfl" to "NFL",
            "epl" to "英超",
            "lal" to "西甲",
            "mlb" to "MLB",
            "nhl" to "NHL",
            "ufc" to "UFC"
        )
    }

    @Transactional
    fun create(request: SportsTailStrategyCreateRequest): Result<SportsTailStrategyDto> {
        return try {
            if (request.accountId <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ACCOUNT_ID_INVALID.messageKey))
            }
            if (request.conditionId.isBlank()) {
                return Result.failure(IllegalArgumentException(ErrorCode.SPORTS_TAIL_STRATEGY_CONDITION_ID_EMPTY.messageKey))
            }

            val triggerPrice = request.triggerPrice.toSafeBigDecimal()
            if (triggerPrice <= BigDecimal.ZERO || triggerPrice >= BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException(ErrorCode.SPORTS_TAIL_STRATEGY_PRICE_INVALID.messageKey))
            }

            val amountMode = request.amountMode.uppercase()
            if (amountMode != "FIXED" && amountMode != "RATIO") {
                return Result.failure(IllegalArgumentException(ErrorCode.SPORTS_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey))
            }

            val amountValue = request.amountValue.toSafeBigDecimal()
            if (amountValue <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }

            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.ACCOUNT_NOT_FOUND.messageKey))

            val existing = strategyRepository.findByAccountIdAndConditionId(request.accountId, request.conditionId)
            if (existing != null) {
                return Result.failure(IllegalArgumentException("该市场已存在策略"))
            }

            val takeProfitPrice = request.takeProfitPrice?.takeIf { it.isNotBlank() }?.toSafeBigDecimal()
            val stopLossPrice = request.stopLossPrice?.takeIf { it.isNotBlank() }?.toSafeBigDecimal()

            val marketInfo = runBlocking { fetchMarketInfo(request.conditionId).getOrNull() }

            val entity = SportsTailStrategy(
                accountId = request.accountId,
                conditionId = request.conditionId,
                marketTitle = request.marketTitle.takeIf { it.isNotBlank() } ?: marketInfo?.question,
                eventSlug = request.eventSlug ?: marketInfo?.eventSlug,
                yesTokenId = marketInfo?.yesTokenId,
                noTokenId = marketInfo?.noTokenId,
                triggerPrice = triggerPrice,
                amountMode = amountMode,
                amountValue = amountValue,
                takeProfitPrice = takeProfitPrice,
                stopLossPrice = stopLossPrice
            )
            val saved = strategyRepository.save(entity)
            eventPublisher.publishEvent(SportsTailStrategyChangedEvent(this))
            Result.success(entityToDto(saved, account))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("创建体育尾盘策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun delete(id: Long): Result<Unit> {
        return try {
            val existing = strategyRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.SPORTS_TAIL_STRATEGY_NOT_FOUND.messageKey))

            if (existing.filled && !existing.sold) {
                return Result.failure(IllegalArgumentException("已成交未卖出的策略不能删除"))
            }

            strategyRepository.deleteById(id)
            eventPublisher.publishEvent(SportsTailStrategyChangedEvent(this))
            Result.success(Unit)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("删除体育尾盘策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun list(request: SportsTailStrategyListRequest): Result<SportsTailStrategyListResponse> {
        return try {
            val list = when {
                request.accountId != null -> strategyRepository.findAllByAccountIdOrderByCreatedAtDesc(request.accountId)
                else -> strategyRepository.findAllByOrderByCreatedAtDesc()
            }

            val accountIds = list.map { it.accountId }.distinct()
            val accountMap = accountRepository.findAllById(accountIds).associateBy { it.id }

            val dtos = list.map { entityToDto(it, accountMap[it.accountId]) }
            Result.success(SportsTailStrategyListResponse(list = dtos))
        } catch (e: Exception) {
            logger.error("查询体育尾盘策略列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getTriggers(request: SportsTailTriggerListRequest): Result<SportsTailTriggerListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val startTs = request.startTime ?: 0L
            val endTs = request.endTime ?: Long.MAX_VALUE
            val useTimeRange = request.startTime != null || request.endTime != null
            val useStatus = !request.status.isNullOrBlank()

            val pageResult: Page<SportsTailStrategyTrigger> = when {
                request.accountId != null && useTimeRange && useStatus ->
                    triggerRepository.findAllByAccountIdAndBuyStatusAndTriggeredAtBetweenOrderByTriggeredAtDesc(
                        request.accountId, request.status!!, startTs, endTs, page
                    )
                request.accountId != null && useTimeRange ->
                    triggerRepository.findAllByAccountIdAndTriggeredAtBetweenOrderByTriggeredAtDesc(
                        request.accountId, startTs, endTs, page
                    )
                request.accountId != null && useStatus ->
                    triggerRepository.findAllByAccountIdAndBuyStatusOrderByTriggeredAtDesc(
                        request.accountId, request.status!!, page
                    )
                request.accountId != null ->
                    triggerRepository.findAllByAccountIdOrderByTriggeredAtDesc(request.accountId, page)
                useTimeRange && useStatus ->
                    triggerRepository.findAllByBuyStatusAndTriggeredAtBetweenOrderByTriggeredAtDesc(
                        request.status!!, startTs, endTs, page
                    )
                useTimeRange ->
                    triggerRepository.findAllByTriggeredAtBetweenOrderByTriggeredAtDesc(startTs, endTs, page)
                useStatus ->
                    triggerRepository.findAllByBuyStatusOrderByTriggeredAtDesc(request.status!!, page)
                else ->
                    triggerRepository.findAllByOrderByTriggeredAtDesc(page)
            }

            val total = pageResult.totalElements
            val list = pageResult.content.map { triggerToDto(it) }
            Result.success(SportsTailTriggerListResponse(total = total, list = list))
        } catch (e: Exception) {
            logger.error("查询触发记录失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getSportsCategories(): Result<SportsCategoryListResponse> {
        return try {
            val api = retrofitFactory.createGammaSportsApi()
            val response = api.getSports()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val list = body.map { c -> categoryToDto(c) }
                Result.success(SportsCategoryListResponse(list = list))
            } else {
                logger.warn("获取体育类别失败: ${response.code()}")
                Result.failure(Exception("获取体育类别失败"))
            }
        } catch (e: Exception) {
            logger.error("获取体育类别失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun searchMarkets(request: SportsMarketSearchRequest): Result<SportsMarketSearchResponse> {
        return try {
            val api = retrofitFactory.createGammaSportsApi()

            val tagId = if (!request.sport.isNullOrBlank()) {
                getTagIdBySport(request.sport)
            } else null

            val response = api.searchMarkets(
                tagId = tagId,
                active = true,
                closed = false,
                limit = request.limit,
                order = "endDate",
                ascending = true,
                slug = request.keyword
            )

            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val filtered = if (!request.minLiquidity.isNullOrBlank()) {
                    val minLiquidity = request.minLiquidity.toSafeBigDecimal()
                    markets.filter { m ->
                        val liquidity = m.liquidityNum?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        liquidity >= minLiquidity
                    }
                } else {
                    markets
                }
                val list = filtered.map { m -> marketToDto(m) }
                Result.success(SportsMarketSearchResponse(list = list))
            } else {
                logger.warn("搜索市场失败: ${response.code()}")
                Result.failure(Exception("搜索市场失败"))
            }
        } catch (e: Exception) {
            logger.error("搜索市场失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getMarketDetail(conditionId: String): Result<SportsMarketDetailResponse> {
        return try {
            val marketInfo = fetchMarketInfo(conditionId).getOrNull()
                ?: return Result.failure(Exception("市场不存在"))

            Result.success(
                SportsMarketDetailResponse(
                    conditionId = marketInfo.conditionId,
                    question = marketInfo.question,
                    outcomes = marketInfo.outcomes,
                    outcomePrices = marketInfo.outcomePrices,
                    endDate = marketInfo.endDate,
                    liquidity = marketInfo.liquidity,
                    bestBid = marketInfo.bestBid,
                    bestAsk = marketInfo.bestAsk,
                    yesTokenId = marketInfo.yesTokenId,
                    noTokenId = marketInfo.noTokenId,
                    eventSlug = marketInfo.eventSlug
                )
            )
        } catch (e: Exception) {
            logger.error("获取市场详情失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchMarketInfo(conditionId: String): Result<SportsMarketDto> {
        return try {
            val api = retrofitFactory.createGammaApi()
            val response = api.listMarkets(conditionIds = listOf(conditionId))
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val m = response.body()!![0]
                Result.success(marketResponseToDto(m))
            } else {
                Result.failure(Exception("市场不存在"))
            }
        } catch (e: Exception) {
            logger.error("获取市场信息失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun getTagIdBySport(sport: String): Long? {
        return try {
            val api = retrofitFactory.createGammaSportsApi()
            val response = api.getSports()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val category = body.find { c -> c.sport == sport.lowercase() }
                category?.tags?.split(",")?.firstOrNull()?.toLongOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        return clobTokenIds.fromJson<List<String>>() ?: emptyList()
    }

    private fun parseOutcomes(outcomes: String?): List<String> {
        if (outcomes.isNullOrBlank()) return emptyList()
        return outcomes.fromJson<List<String>>() ?: emptyList()
    }

    private fun parseOutcomePrices(outcomePrices: String?): List<String> {
        if (outcomePrices.isNullOrBlank()) return emptyList()
        return outcomePrices.fromJson<List<String>>() ?: emptyList()
    }

    private fun entityToDto(e: SportsTailStrategy, account: Account?): SportsTailStrategyDto {
        return SportsTailStrategyDto(
            id = e.id ?: 0L,
            accountId = e.accountId,
            accountName = account?.accountName ?: account?.walletAddress?.take(8),
            conditionId = e.conditionId,
            marketTitle = e.marketTitle,
            eventSlug = e.eventSlug,
            triggerPrice = e.triggerPrice.toPlainString(),
            amountMode = e.amountMode,
            amountValue = e.amountValue.toPlainString(),
            takeProfitPrice = e.takeProfitPrice?.toPlainString(),
            stopLossPrice = e.stopLossPrice?.toPlainString(),
            filled = e.filled,
            filledPrice = e.filledPrice?.toPlainString(),
            filledOutcomeIndex = e.filledOutcomeIndex,
            filledOutcomeName = e.filledOutcomeName,
            filledAmount = e.filledAmount?.toPlainString(),
            filledShares = e.filledShares?.toPlainString(),
            filledAt = e.filledAt,
            sold = e.sold,
            sellPrice = e.sellPrice?.toPlainString(),
            sellType = e.sellType,
            sellAmount = e.sellAmount?.toPlainString(),
            realizedPnl = e.realizedPnl?.toPlainString(),
            soldAt = e.soldAt,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt
        )
    }

    private fun categoryToDto(c: com.wrbug.polymarketbot.api.SportsCategoryResponse): SportsCategoryDto {
        val tagId = c.tags?.split(",")?.firstOrNull()?.toLongOrNull() ?: 0L
        return SportsCategoryDto(
            sport = c.sport ?: "",
            image = c.image,
            tagId = tagId,
            name = SPORT_NAMES[c.sport] ?: c.sport ?: ""
        )
    }

    private fun marketResponseToDto(m: MarketResponse): SportsMarketDto {
        val tokenIds = parseClobTokenIds(m.clobTokenIds ?: m.clob_token_ids)
        return SportsMarketDto(
            conditionId = m.conditionId ?: "",
            question = m.question ?: "",
            outcomes = parseOutcomes(m.outcomes),
            outcomePrices = parseOutcomePrices(m.outcomePrices),
            endDate = m.endDate,
            liquidity = m.liquidityNum?.toString() ?: m.liquidity,
            bestBid = m.bestBid,
            bestAsk = m.bestAsk,
            yesTokenId = tokenIds.getOrNull(0),
            noTokenId = tokenIds.getOrNull(1),
            eventSlug = m.events?.firstOrNull()?.slug
        )
    }

    private fun marketToDto(m: com.wrbug.polymarketbot.api.SportsMarketResponse): SportsMarketDto {
        val tokenIds = parseClobTokenIds(m.clobTokenIds)
        return SportsMarketDto(
            conditionId = m.conditionId ?: "",
            question = m.question ?: "",
            outcomes = parseOutcomes(m.outcomes),
            outcomePrices = parseOutcomePrices(m.outcomePrices),
            endDate = m.endDate,
            liquidity = m.liquidityNum?.toString() ?: m.liquidity,
            bestBid = m.bestBid,
            bestAsk = m.bestAsk,
            yesTokenId = tokenIds.getOrNull(0),
            noTokenId = tokenIds.getOrNull(1),
            eventSlug = m.events?.firstOrNull()?.slug
        )
    }

    private fun triggerToDto(t: SportsTailStrategyTrigger): SportsTailTriggerDto {
        return SportsTailTriggerDto(
            id = t.id ?: 0L,
            strategyId = t.strategyId,
            marketTitle = t.marketTitle,
            conditionId = t.conditionId,
            buyPrice = t.buyPrice.toPlainString(),
            outcomeIndex = t.outcomeIndex,
            outcomeName = t.outcomeName,
            buyAmount = t.buyAmount.toPlainString(),
            buyShares = t.buyShares?.toPlainString(),
            buyStatus = t.buyStatus,
            sellPrice = t.sellPrice?.toPlainString(),
            sellType = t.sellType,
            sellAmount = t.sellAmount?.toPlainString(),
            sellStatus = t.sellStatus,
            realizedPnl = t.realizedPnl?.toPlainString(),
            triggeredAt = t.triggeredAt,
            soldAt = t.soldAt
        )
    }
}
