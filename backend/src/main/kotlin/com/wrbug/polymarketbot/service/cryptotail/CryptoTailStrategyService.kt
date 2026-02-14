package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class CryptoTailStrategyService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyService::class.java)

    private val maxWindowByInterval = mapOf(300 to 300, 900 to 900)

    @Transactional
    fun create(request: CryptoTailStrategyCreateRequest): Result<CryptoTailStrategyDto> {
        return try {
            if (request.accountId <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ACCOUNT_ID_INVALID.messageKey))
            }
            if (request.marketSlugPrefix.isBlank()) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val interval = request.intervalSeconds
            if (interval != 300 && interval != 900) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID.messageKey))
            }
            val maxWindow = maxWindowByInterval[interval] ?: 300
            if (request.windowStartSeconds > request.windowEndSeconds) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey))
            }
            if (request.windowEndSeconds > maxWindow) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey))
            }
            val amountMode = request.amountMode.uppercase()
            if (amountMode != "RATIO" && amountMode != "FIXED") {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey))
            }
            val minPrice = request.minPrice.toSafeBigDecimal()
            val maxPrice = (request.maxPrice ?: "1").toSafeBigDecimal()
            if (minPrice > maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val amountValue = request.amountValue.toSafeBigDecimal()
            if (amountValue <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val minSpreadMode = (request.minSpreadMode ?: "NONE").uppercase()
            if (minSpreadMode != "NONE" && minSpreadMode != "FIXED" && minSpreadMode != "AUTO") {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val minSpreadValue = request.minSpreadValue?.toSafeBigDecimal()
            if (minSpreadMode == "FIXED" && (minSpreadValue == null || minSpreadValue < BigDecimal.ZERO)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }

            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName(request.marketSlugPrefix.trim())

            val entity = CryptoTailStrategy(
                accountId = request.accountId,
                name = nameToSave,
                marketSlugPrefix = request.marketSlugPrefix.trim(),
                intervalSeconds = interval,
                windowStartSeconds = request.windowStartSeconds,
                windowEndSeconds = request.windowEndSeconds,
                minPrice = minPrice,
                maxPrice = maxPrice,
                amountMode = amountMode,
                amountValue = amountValue,
                minSpreadMode = minSpreadMode,
                minSpreadValue = minSpreadValue,
                enabled = request.enabled
            )
            val saved = strategyRepository.save(entity)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            Result.success(entityToDto(saved, null))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("创建尾盘策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun update(request: CryptoTailStrategyUpdateRequest): Result<CryptoTailStrategyDto> {
        return try {
            val existing = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            val interval = existing.intervalSeconds
            val maxWindow = maxWindowByInterval[interval] ?: 300

            request.windowStartSeconds?.let { ws ->
                request.windowEndSeconds?.let { we ->
                    if (ws > we) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey))
                    if (we > maxWindow) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey))
                }
            }
            request.windowStartSeconds?.let { if (it > (request.windowEndSeconds ?: existing.windowEndSeconds)) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey)) }
            request.windowEndSeconds?.let { if (it > maxWindow) return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey)) }

            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: existing.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName(existing.marketSlugPrefix)

            val newMinSpreadMode = request.minSpreadMode?.uppercase() ?: existing.minSpreadMode
            if (newMinSpreadMode != "NONE" && newMinSpreadMode != "FIXED" && newMinSpreadMode != "AUTO") {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val newMinSpreadValue = request.minSpreadValue?.toSafeBigDecimal() ?: existing.minSpreadValue
            if (newMinSpreadMode == "FIXED" && (newMinSpreadValue == null || newMinSpreadValue < BigDecimal.ZERO)) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }

            val updated = existing.copy(
                name = nameToSave,
                windowStartSeconds = request.windowStartSeconds ?: existing.windowStartSeconds,
                windowEndSeconds = request.windowEndSeconds ?: existing.windowEndSeconds,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: existing.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: existing.maxPrice,
                amountMode = request.amountMode?.uppercase() ?: existing.amountMode,
                amountValue = request.amountValue?.toSafeBigDecimal() ?: existing.amountValue,
                minSpreadMode = newMinSpreadMode,
                minSpreadValue = newMinSpreadValue,
                enabled = request.enabled ?: existing.enabled,
                updatedAt = System.currentTimeMillis()
            )
            if (updated.minPrice > updated.maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            request.amountMode?.uppercase()?.let { if (it != "RATIO" && it != "FIXED") return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey)) }
            val saved = strategyRepository.save(updated)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            val lastTrigger = triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(saved.id!!, PageRequest.of(0, 1))
                .content.firstOrNull()?.createdAt
            Result.success(entityToDto(saved, lastTrigger))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("更新尾盘策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun delete(strategyId: Long): Result<Unit> {
        return try {
            if (!strategyRepository.existsById(strategyId)) {
                return Result.failure(IllegalArgumentException(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey))
            }
            strategyRepository.deleteById(strategyId)
            eventPublisher.publishEvent(CryptoTailStrategyChangedEvent(this))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除尾盘策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun list(request: CryptoTailStrategyListRequest): Result<CryptoTailStrategyListResponse> {
        return try {
            val list = when {
                request.accountId != null && request.enabled != null -> strategyRepository.findByAccountIdAndEnabled(request.accountId, request.enabled)
                request.accountId != null -> strategyRepository.findAllByAccountId(request.accountId)
                request.enabled == true -> strategyRepository.findAllByEnabledTrue()
                request.enabled == false -> strategyRepository.findAll().filter { !it.enabled }
                else -> strategyRepository.findAll()
            }
            val lastTriggerMap = list.map { it.id!! }.associateWith { id ->
                triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(id, PageRequest.of(0, 1))
                    .content.firstOrNull()?.createdAt
            }
            val dtos = list.map { entityToDto(it, lastTriggerMap[it.id]) }
            Result.success(CryptoTailStrategyListResponse(list = dtos))
        } catch (e: Exception) {
            logger.error("查询尾盘策略列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getTriggerRecords(request: CryptoTailStrategyTriggerListRequest): Result<CryptoTailStrategyTriggerListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val startTs = request.startDate ?: 0L
            val endTs = request.endDate ?: Long.MAX_VALUE
            val useTimeRange = request.startDate != null || request.endDate != null
            val pageResult = when {
                useTimeRange && request.status != null && request.status.isNotBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, request.status, startTs, endTs, page
                    )
                useTimeRange ->
                    triggerRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, startTs, endTs, page
                    )
                request.status != null && request.status.isNotBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusOrderByCreatedAtDesc(request.strategyId, request.status, page)
                else ->
                    triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, page)
            }
            val list = pageResult.content.map { triggerToDto(it) }
            val total = when {
                useTimeRange && request.status != null && request.status.isNotBlank() ->
                    triggerRepository.countByStrategyIdAndStatusAndCreatedAtBetween(request.strategyId, request.status, startTs, endTs)
                useTimeRange ->
                    triggerRepository.countByStrategyIdAndCreatedAtBetween(request.strategyId, startTs, endTs)
                request.status != null && request.status.isNotBlank() ->
                    triggerRepository.countByStrategyIdAndStatus(request.strategyId, request.status)
                else ->
                    pageResult.totalElements
            }
            Result.success(CryptoTailStrategyTriggerListResponse(list = list, total = total))
        } catch (e: Exception) {
            logger.error("查询触发记录失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getStrategy(strategyId: Long): CryptoTailStrategy? = strategyRepository.findById(strategyId).orElse(null)

    private fun generateStrategyName(marketSlugPrefix: String): String {
        val suffix = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        return "尾盘策略-${marketSlugPrefix}-$suffix"
    }

    private fun entityToDto(e: CryptoTailStrategy, lastTriggerAt: Long?): CryptoTailStrategyDto {
        val strategyId = e.id ?: 0L
        val totalPnl = triggerRepository.sumRealizedPnlByStrategyId(strategyId)
        val settledCount = triggerRepository.countResolvedByStrategyId(strategyId)
        val winCount = triggerRepository.countWinsByStrategyId(strategyId)
        val winRateStr = if (settledCount > 0L) {
            BigDecimal(winCount).divide(BigDecimal(settledCount), 4, java.math.RoundingMode.HALF_UP).toPlainString()
        } else null
        return CryptoTailStrategyDto(
            id = strategyId,
            accountId = e.accountId,
            name = e.name,
            marketSlugPrefix = e.marketSlugPrefix,
            marketTitle = null,
            intervalSeconds = e.intervalSeconds,
            windowStartSeconds = e.windowStartSeconds,
            windowEndSeconds = e.windowEndSeconds,
            minPrice = e.minPrice.toPlainString(),
            maxPrice = e.maxPrice.toPlainString(),
            amountMode = e.amountMode,
            amountValue = e.amountValue.toPlainString(),
            minSpreadMode = e.minSpreadMode,
            minSpreadValue = e.minSpreadValue?.toPlainString(),
            enabled = e.enabled,
            lastTriggerAt = lastTriggerAt,
            totalRealizedPnl = totalPnl?.toPlainString(),
            settledCount = settledCount,
            winCount = winCount,
            winRate = winRateStr,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt
        )
    }

    private fun triggerToDto(t: CryptoTailStrategyTrigger): CryptoTailStrategyTriggerDto = CryptoTailStrategyTriggerDto(
        id = t.id ?: 0L,
        strategyId = t.strategyId,
        periodStartUnix = t.periodStartUnix,
        marketTitle = t.marketTitle,
        outcomeIndex = t.outcomeIndex,
        triggerPrice = t.triggerPrice.toPlainString(),
        amountUsdc = t.amountUsdc.toPlainString(),
        orderId = t.orderId,
        status = t.status,
        failReason = t.failReason,
        resolved = t.resolved,
        realizedPnl = t.realizedPnl?.toPlainString(),
        winnerOutcomeIndex = t.winnerOutcomeIndex,
        settledAt = t.settledAt,
        createdAt = t.createdAt
    )
}
