package com.wrbug.polymarketbot.service.nba

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.NbaQuantitativeStrategy
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.NbaQuantitativeStrategyRepository
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * NBA 量化策略服务
 */
@Service
class NbaQuantitativeStrategyService(
    private val strategyRepository: NbaQuantitativeStrategyRepository,
    private val accountRepository: AccountRepository
) {
    private val logger = LoggerFactory.getLogger(NbaQuantitativeStrategyService::class.java)
    
    /**
     * 创建策略
     */
    @Transactional
    suspend fun createStrategy(request: NbaQuantitativeStrategyCreateRequest): Result<NbaQuantitativeStrategyDto> {
        return try {
            // 验证账户是否存在
            val account = accountRepository.findById(request.accountId).orElse(null)
            if (account == null) {
                return Result.failure(IllegalArgumentException("账户不存在"))
            }
            
            // 验证策略名称是否重复
            val existing = strategyRepository.findByStrategyName(request.strategyName)
            if (existing != null) {
                return Result.failure(IllegalArgumentException("策略名称已存在"))
            }
            
            // 创建策略实体
            val strategy = NbaQuantitativeStrategy(
                strategyName = request.strategyName,
                strategyDescription = request.strategyDescription,
                accountId = request.accountId,
                enabled = request.enabled,
                filterTeams = request.filterTeams?.let { JsonUtils.toJson(it) },
                filterDateFrom = request.filterDateFrom,
                filterDateTo = request.filterDateTo,
                filterGameImportance = request.filterGameImportance,
                minWinProbabilityDiff = request.minWinProbabilityDiff ?: BigDecimal("0.1"),
                minWinProbability = request.minWinProbability,
                maxWinProbability = request.maxWinProbability,
                minTradeValue = request.minTradeValue ?: BigDecimal("0.05"),
                minRemainingTime = request.minRemainingTime,
                maxRemainingTime = request.maxRemainingTime,
                minScoreDiff = request.minScoreDiff,
                maxScoreDiff = request.maxScoreDiff,
                buyAmountStrategy = request.buyAmountStrategy ?: "FIXED",
                fixedBuyAmount = request.fixedBuyAmount,
                buyRatio = request.buyRatio,
                baseBuyAmount = request.baseBuyAmount,
                buyTiming = request.buyTiming ?: "IMMEDIATE",
                delayBuySeconds = request.delayBuySeconds ?: 0,
                buyDirection = request.buyDirection ?: "AUTO",
                enableSell = request.enableSell ?: true,
                takeProfitThreshold = request.takeProfitThreshold,
                stopLossThreshold = request.stopLossThreshold,
                probabilityReversalThreshold = request.probabilityReversalThreshold,
                sellRatio = request.sellRatio ?: BigDecimal("1.0"),
                sellTiming = request.sellTiming ?: "IMMEDIATE",
                delaySellSeconds = request.delaySellSeconds ?: 0,
                priceStrategy = request.priceStrategy ?: "MARKET",
                fixedPrice = request.fixedPrice,
                priceOffset = request.priceOffset ?: BigDecimal.ZERO,
                maxPosition = request.maxPosition ?: BigDecimal("50"),
                minPosition = request.minPosition ?: BigDecimal("5"),
                maxGamePosition = request.maxGamePosition,
                maxDailyLoss = request.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders,
                maxDailyProfit = request.maxDailyProfit,
                priceTolerance = request.priceTolerance ?: BigDecimal("0.05"),
                minProbabilityThreshold = request.minProbabilityThreshold,
                maxProbabilityThreshold = request.maxProbabilityThreshold,
                baseStrengthWeight = request.baseStrengthWeight ?: BigDecimal("0.3"),
                recentFormWeight = request.recentFormWeight ?: BigDecimal("0.25"),
                lineupIntegrityWeight = request.lineupIntegrityWeight ?: BigDecimal("0.2"),
                starStatusWeight = request.starStatusWeight ?: BigDecimal("0.15"),
                environmentWeight = request.environmentWeight ?: BigDecimal("0.1"),
                matchupAdvantageWeight = request.matchupAdvantageWeight ?: BigDecimal("0.2"),
                scoreDiffWeight = request.scoreDiffWeight ?: BigDecimal("0.3"),
                momentumWeight = request.momentumWeight ?: BigDecimal("0.2"),
                dataUpdateFrequency = request.dataUpdateFrequency ?: 30,
                analysisFrequency = request.analysisFrequency ?: 30,
                pushFailedOrders = request.pushFailedOrders ?: false,
                pushFrequency = request.pushFrequency ?: "REALTIME",
                batchPushInterval = request.batchPushInterval ?: 1
            )
            
            val saved = strategyRepository.save(strategy)
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("创建策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新策略
     */
    @Transactional
    suspend fun updateStrategy(request: NbaQuantitativeStrategyUpdateRequest): Result<NbaQuantitativeStrategyDto> {
        return try {
            val strategy = strategyRepository.findById(request.id).orElse(null)
            if (strategy == null) {
                return Result.failure(IllegalArgumentException("策略不存在"))
            }
            
            // 更新字段（只更新提供的字段）
            val updated = strategy.copy(
                strategyName = request.strategyName ?: strategy.strategyName,
                strategyDescription = request.strategyDescription ?: strategy.strategyDescription,
                enabled = request.enabled ?: strategy.enabled,
                filterTeams = request.filterTeams?.let { JsonUtils.toJson(it) } ?: strategy.filterTeams,
                filterDateFrom = request.filterDateFrom ?: strategy.filterDateFrom,
                filterDateTo = request.filterDateTo ?: strategy.filterDateTo,
                filterGameImportance = request.filterGameImportance ?: strategy.filterGameImportance,
                minWinProbabilityDiff = request.minWinProbabilityDiff ?: strategy.minWinProbabilityDiff,
                minWinProbability = request.minWinProbability ?: strategy.minWinProbability,
                maxWinProbability = request.maxWinProbability ?: strategy.maxWinProbability,
                minTradeValue = request.minTradeValue ?: strategy.minTradeValue,
                minRemainingTime = request.minRemainingTime ?: strategy.minRemainingTime,
                maxRemainingTime = request.maxRemainingTime ?: strategy.maxRemainingTime,
                minScoreDiff = request.minScoreDiff ?: strategy.minScoreDiff,
                maxScoreDiff = request.maxScoreDiff ?: strategy.maxScoreDiff,
                buyAmountStrategy = request.buyAmountStrategy ?: strategy.buyAmountStrategy,
                fixedBuyAmount = request.fixedBuyAmount ?: strategy.fixedBuyAmount,
                buyRatio = request.buyRatio ?: strategy.buyRatio,
                baseBuyAmount = request.baseBuyAmount ?: strategy.baseBuyAmount,
                buyTiming = request.buyTiming ?: strategy.buyTiming,
                delayBuySeconds = request.delayBuySeconds ?: strategy.delayBuySeconds,
                buyDirection = request.buyDirection ?: strategy.buyDirection,
                enableSell = request.enableSell ?: strategy.enableSell,
                takeProfitThreshold = request.takeProfitThreshold ?: strategy.takeProfitThreshold,
                stopLossThreshold = request.stopLossThreshold ?: strategy.stopLossThreshold,
                probabilityReversalThreshold = request.probabilityReversalThreshold ?: strategy.probabilityReversalThreshold,
                sellRatio = request.sellRatio ?: strategy.sellRatio,
                sellTiming = request.sellTiming ?: strategy.sellTiming,
                delaySellSeconds = request.delaySellSeconds ?: strategy.delaySellSeconds,
                priceStrategy = request.priceStrategy ?: strategy.priceStrategy,
                fixedPrice = request.fixedPrice ?: strategy.fixedPrice,
                priceOffset = request.priceOffset ?: strategy.priceOffset,
                maxPosition = request.maxPosition ?: strategy.maxPosition,
                minPosition = request.minPosition ?: strategy.minPosition,
                maxGamePosition = request.maxGamePosition ?: strategy.maxGamePosition,
                maxDailyLoss = request.maxDailyLoss ?: strategy.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: strategy.maxDailyOrders,
                maxDailyProfit = request.maxDailyProfit ?: strategy.maxDailyProfit,
                priceTolerance = request.priceTolerance ?: strategy.priceTolerance,
                minProbabilityThreshold = request.minProbabilityThreshold ?: strategy.minProbabilityThreshold,
                maxProbabilityThreshold = request.maxProbabilityThreshold ?: strategy.maxProbabilityThreshold,
                baseStrengthWeight = request.baseStrengthWeight ?: strategy.baseStrengthWeight,
                recentFormWeight = request.recentFormWeight ?: strategy.recentFormWeight,
                lineupIntegrityWeight = request.lineupIntegrityWeight ?: strategy.lineupIntegrityWeight,
                starStatusWeight = request.starStatusWeight ?: strategy.starStatusWeight,
                environmentWeight = request.environmentWeight ?: strategy.environmentWeight,
                matchupAdvantageWeight = request.matchupAdvantageWeight ?: strategy.matchupAdvantageWeight,
                scoreDiffWeight = request.scoreDiffWeight ?: strategy.scoreDiffWeight,
                momentumWeight = request.momentumWeight ?: strategy.momentumWeight,
                dataUpdateFrequency = request.dataUpdateFrequency ?: strategy.dataUpdateFrequency,
                analysisFrequency = request.analysisFrequency ?: strategy.analysisFrequency,
                pushFailedOrders = request.pushFailedOrders ?: strategy.pushFailedOrders,
                pushFrequency = request.pushFrequency ?: strategy.pushFrequency,
                batchPushInterval = request.batchPushInterval ?: strategy.batchPushInterval,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = strategyRepository.save(updated)
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取策略列表
     */
    suspend fun getStrategyList(request: NbaQuantitativeStrategyListRequest): Result<NbaQuantitativeStrategyListResponse> {
        return try {
            val page = request.page ?: 1
            val limit = request.limit ?: 20
            val pageable = PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "createdAt"))
            
            val strategies = when {
                request.accountId != null && request.enabled != null -> {
                    strategyRepository.findByAccountIdAndEnabled(request.accountId, request.enabled)
                }
                request.accountId != null -> {
                    strategyRepository.findByAccountId(request.accountId)
                }
                request.enabled != null -> {
                    strategyRepository.findByEnabled(request.enabled)
                }
                else -> {
                    strategyRepository.findAll(pageable).content
                }
            }
            
            // 过滤策略名称（如果提供）
            val filtered = if (request.strategyName != null) {
                strategies.filter { it.strategyName.contains(request.strategyName, ignoreCase = true) }
            } else {
                strategies
            }
            
            val total = filtered.size.toLong()
            val dtoList = filtered.map { toDto(it) }
            
            Result.success(
                NbaQuantitativeStrategyListResponse(
                    list = dtoList,
                    total = total,
                    page = page,
                    limit = limit
                )
            )
        } catch (e: Exception) {
            logger.error("获取策略列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取策略详情
     */
    suspend fun getStrategyDetail(id: Long): Result<NbaQuantitativeStrategyDto> {
        return try {
            val strategy = strategyRepository.findById(id).orElse(null)
            if (strategy == null) {
                return Result.failure(IllegalArgumentException("策略不存在"))
            }
            Result.success(toDto(strategy))
        } catch (e: Exception) {
            logger.error("获取策略详情失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除策略
     */
    @Transactional
    suspend fun deleteStrategy(id: Long): Result<Unit> {
        return try {
            val strategy = strategyRepository.findById(id).orElse(null)
            if (strategy == null) {
                return Result.failure(IllegalArgumentException("策略不存在"))
            }
            strategyRepository.delete(strategy)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取启用的策略列表
     */
    suspend fun getEnabledStrategies(): List<NbaQuantitativeStrategy> {
        return strategyRepository.findByEnabled(true)
    }
    
    /**
     * 转换为 DTO
     */
    private fun toDto(strategy: NbaQuantitativeStrategy): NbaQuantitativeStrategyDto {
        val account = accountRepository.findById(strategy.accountId).orElse(null)
        return NbaQuantitativeStrategyDto(
            id = strategy.id,
            strategyName = strategy.strategyName,
            strategyDescription = strategy.strategyDescription,
            accountId = strategy.accountId,
            accountName = account?.accountName,
            enabled = strategy.enabled,
            filterTeams = strategy.filterTeams?.let { JsonUtils.parseStringList(it) },
            filterDateFrom = strategy.filterDateFrom,
            filterDateTo = strategy.filterDateTo,
            filterGameImportance = strategy.filterGameImportance,
            minWinProbabilityDiff = strategy.minWinProbabilityDiff,
            minWinProbability = strategy.minWinProbability,
            maxWinProbability = strategy.maxWinProbability,
            minTradeValue = strategy.minTradeValue,
            minRemainingTime = strategy.minRemainingTime,
            maxRemainingTime = strategy.maxRemainingTime,
            minScoreDiff = strategy.minScoreDiff,
            maxScoreDiff = strategy.maxScoreDiff,
            buyAmountStrategy = strategy.buyAmountStrategy,
            fixedBuyAmount = strategy.fixedBuyAmount,
            buyRatio = strategy.buyRatio,
            baseBuyAmount = strategy.baseBuyAmount,
            buyTiming = strategy.buyTiming,
            delayBuySeconds = strategy.delayBuySeconds,
            buyDirection = strategy.buyDirection,
            enableSell = strategy.enableSell,
            takeProfitThreshold = strategy.takeProfitThreshold,
            stopLossThreshold = strategy.stopLossThreshold,
            probabilityReversalThreshold = strategy.probabilityReversalThreshold,
            sellRatio = strategy.sellRatio,
            sellTiming = strategy.sellTiming,
            delaySellSeconds = strategy.delaySellSeconds,
            priceStrategy = strategy.priceStrategy,
            fixedPrice = strategy.fixedPrice,
            priceOffset = strategy.priceOffset,
            maxPosition = strategy.maxPosition,
            minPosition = strategy.minPosition,
            maxGamePosition = strategy.maxGamePosition,
            maxDailyLoss = strategy.maxDailyLoss,
            maxDailyOrders = strategy.maxDailyOrders,
            maxDailyProfit = strategy.maxDailyProfit,
            priceTolerance = strategy.priceTolerance,
            minProbabilityThreshold = strategy.minProbabilityThreshold,
            maxProbabilityThreshold = strategy.maxProbabilityThreshold,
            baseStrengthWeight = strategy.baseStrengthWeight,
            recentFormWeight = strategy.recentFormWeight,
            lineupIntegrityWeight = strategy.lineupIntegrityWeight,
            starStatusWeight = strategy.starStatusWeight,
            environmentWeight = strategy.environmentWeight,
            matchupAdvantageWeight = strategy.matchupAdvantageWeight,
            scoreDiffWeight = strategy.scoreDiffWeight,
            momentumWeight = strategy.momentumWeight,
            dataUpdateFrequency = strategy.dataUpdateFrequency,
            analysisFrequency = strategy.analysisFrequency,
            pushFailedOrders = strategy.pushFailedOrders,
            pushFrequency = strategy.pushFrequency,
            batchPushInterval = strategy.batchPushInterval,
            createdAt = strategy.createdAt,
            updatedAt = strategy.updatedAt
        )
    }
}

