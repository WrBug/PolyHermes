package com.wrbug.polymarketbot.service.whalemonitor

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.WhaleMonitorStrategy
import com.wrbug.polymarketbot.entity.WhaleMonitorTrigger
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.event.WhaleMonitorStrategyChangedEvent
import com.wrbug.polymarketbot.repository.WhaleMonitorStrategyRepository
import com.wrbug.polymarketbot.repository.WhaleMonitorTriggerRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.toJson
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class WhaleMonitorStrategyService(
    private val strategyRepository: WhaleMonitorStrategyRepository,
    private val triggerRepository: WhaleMonitorTriggerRepository,
    private val marketService: MarketService,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(WhaleMonitorStrategyService::class.java)

    @Transactional
    fun create(request: WhaleMonitorStrategyCreateRequest): Result<WhaleMonitorStrategyDto> {
        return try {
            if (request.accountId <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ACCOUNT_ID_INVALID.messageKey))
            }
            if (request.conditionIds.isEmpty()) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY.messageKey))
            }
            if (request.windowSeconds <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID.messageKey))
            }
            val thresholdAmount = request.thresholdAmount.toSafeBigDecimal()
            if (thresholdAmount <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID.messageKey))
            }
            val orderAmount = request.orderAmount.toSafeBigDecimal()
            if (orderAmount <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID.messageKey))
            }
            if (request.cooldownSeconds <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }
            val minPrice = request.minPrice.toSafeBigDecimal()
            val maxPrice = request.maxPrice.toSafeBigDecimal()
            if (minPrice < BigDecimal.ZERO || maxPrice > BigDecimal.ONE || minPrice > maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID.messageKey))
            }
            validatePriceDecimalPlaces(minPrice, "minPrice")
            validatePriceDecimalPlaces(maxPrice, "maxPrice")

            val conditionIdsJson = request.conditionIds.toJson()
            cacheMarkets(request.conditionIds)
            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName()

            val entity = WhaleMonitorStrategy(
                accountId = request.accountId,
                name = nameToSave,
                conditionIds = conditionIdsJson,
                windowSeconds = request.windowSeconds,
                thresholdAmount = thresholdAmount,
                orderAmount = orderAmount,
                minPrice = minPrice,
                maxPrice = maxPrice,
                cooldownSeconds = request.cooldownSeconds,
                enabled = request.enabled
            )
            val saved = strategyRepository.save(entity)
            eventPublisher.publishEvent(WhaleMonitorStrategyChangedEvent(this))
            Result.success(entityToDto(saved, null, 0L))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("创建大单监听策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun update(request: WhaleMonitorStrategyUpdateRequest): Result<WhaleMonitorStrategyDto> {
        return try {
            val existing = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND.messageKey))

            val updatedConditionIds = request.conditionIds?.also {
                if (it.isEmpty()) return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY.messageKey))
            }
            val conditionIdsJson = updatedConditionIds?.toJson() ?: existing.conditionIds
            if (updatedConditionIds != null) {
                cacheMarkets(updatedConditionIds)
            }

            val windowSeconds = request.windowSeconds ?: existing.windowSeconds
            if (windowSeconds <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID.messageKey))
            }

            val thresholdAmount = request.thresholdAmount?.toSafeBigDecimal() ?: existing.thresholdAmount
            if (thresholdAmount <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID.messageKey))
            }
            val orderAmount = request.orderAmount?.toSafeBigDecimal() ?: existing.orderAmount
            if (orderAmount <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID.messageKey))
            }

            val minPrice = request.minPrice?.toSafeBigDecimal() ?: existing.minPrice
            val maxPrice = request.maxPrice?.toSafeBigDecimal() ?: existing.maxPrice
            if (minPrice < BigDecimal.ZERO || maxPrice > BigDecimal.ONE || minPrice > maxPrice) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID.messageKey))
            }
            validatePriceDecimalPlaces(minPrice, "minPrice")
            validatePriceDecimalPlaces(maxPrice, "maxPrice")

            val cooldownSeconds = request.cooldownSeconds ?: existing.cooldownSeconds
            if (cooldownSeconds <= 0) {
                return Result.failure(IllegalArgumentException(ErrorCode.PARAM_ERROR.messageKey))
            }

            val nameToSave = request.name?.takeIf { it.isNotBlank() }
                ?: existing.name?.takeIf { it.isNotBlank() }
                ?: generateStrategyName()

            val updated = existing.copy(
                name = nameToSave,
                conditionIds = conditionIdsJson,
                windowSeconds = windowSeconds,
                thresholdAmount = thresholdAmount,
                orderAmount = orderAmount,
                minPrice = minPrice,
                maxPrice = maxPrice,
                cooldownSeconds = cooldownSeconds,
                enabled = request.enabled ?: existing.enabled,
                updatedAt = System.currentTimeMillis()
            )
            val saved = strategyRepository.save(updated)
            eventPublisher.publishEvent(WhaleMonitorStrategyChangedEvent(this))
            val lastTrigger = triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(saved.id!!, PageRequest.of(0, 1))
                .content.firstOrNull()?.createdAt
            val triggerCount = triggerRepository.countByStrategyId(saved.id!!)
            Result.success(entityToDto(saved, lastTrigger, triggerCount))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("更新大单监听策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun delete(strategyId: Long): Result<Unit> {
        return try {
            if (!strategyRepository.existsById(strategyId)) {
                return Result.failure(IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND.messageKey))
            }
            strategyRepository.deleteById(strategyId)
            eventPublisher.publishEvent(WhaleMonitorStrategyChangedEvent(this))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除大单监听策略失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun list(request: WhaleMonitorStrategyListRequest): Result<WhaleMonitorStrategyListResponse> {
        return try {
            val list = when {
                request.accountId != null && request.enabled != null -> strategyRepository.findByAccountIdAndEnabled(request.accountId, request.enabled)
                request.accountId != null -> strategyRepository.findAllByAccountId(request.accountId)
                request.enabled == true -> strategyRepository.findAllByEnabledTrue()
                request.enabled == false -> strategyRepository.findAll().filter { !it.enabled }
                else -> strategyRepository.findAll()
            }
            val dtos = list.map { entity ->
                val lastTrigger = if (entity.id != null) {
                    triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(entity.id, PageRequest.of(0, 1))
                        .content.firstOrNull()?.createdAt
                } else null
                val triggerCount = if (entity.id != null) triggerRepository.countByStrategyId(entity.id) else 0L
                entityToDto(entity, lastTrigger, triggerCount)
            }
            Result.success(WhaleMonitorStrategyListResponse(list = dtos))
        } catch (e: Exception) {
            logger.error("查询大单监听策略列表失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getTriggerRecords(request: WhaleMonitorTriggerListRequest): Result<WhaleMonitorTriggerListResponse> {
        return try {
            val page = PageRequest.of((request.page - 1).coerceAtLeast(0), request.pageSize.coerceIn(1, 100))
            val startTs = request.startDate ?: 0L
            val endTs = request.endDate ?: Long.MAX_VALUE
            val useTimeRange = request.startDate != null || request.endDate != null
            val pageResult = when {
                useTimeRange && !request.status.isNullOrBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, request.status, startTs, endTs, page
                    )
                useTimeRange ->
                    triggerRepository.findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        request.strategyId, startTs, endTs, page
                    )
                !request.status.isNullOrBlank() ->
                    triggerRepository.findAllByStrategyIdAndStatusOrderByCreatedAtDesc(request.strategyId, request.status, page)
                else ->
                    triggerRepository.findAllByStrategyIdOrderByCreatedAtDesc(request.strategyId, page)
            }
            val list = pageResult.content.map { triggerToDto(it) }
            val total = when {
                useTimeRange && !request.status.isNullOrBlank() ->
                    triggerRepository.countByStrategyIdAndStatusAndCreatedAtBetween(request.strategyId, request.status, startTs, endTs)
                useTimeRange ->
                    triggerRepository.countByStrategyIdAndCreatedAtBetween(request.strategyId, startTs, endTs)
                !request.status.isNullOrBlank() ->
                    triggerRepository.countByStrategyIdAndStatus(request.strategyId, request.status)
                else ->
                    pageResult.totalElements
            }
            Result.success(WhaleMonitorTriggerListResponse(list = list, total = total))
        } catch (e: Exception) {
            logger.error("查询大单监听触发记录失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getEnabledStrategies(): List<WhaleMonitorStrategy> = strategyRepository.findAllByEnabledTrue()

    fun getStrategy(strategyId: Long): WhaleMonitorStrategy? = strategyRepository.findById(strategyId).orElse(null)

    private fun validatePriceDecimalPlaces(value: BigDecimal, fieldName: String) {
        val scale = value.stripTrailingZeros().scale()
        if (scale > 2) {
            throw IllegalArgumentException(ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID.messageKey)
        }
    }

    private fun generateStrategyName(): String {
        val suffix = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        return "大单监听策略-$suffix"
    }

    /** 将 conditionId 对应市场写入 markets 表（复用 MarketService 缓存与 Gamma 拉取） */
    private fun cacheMarkets(conditionIds: List<String>) {
        if (conditionIds.isEmpty()) return
        marketService.getMarkets(conditionIds)
    }

    private fun buildMarketDtos(conditionIds: List<String>): List<WhaleMonitorMarketDto> {
        if (conditionIds.isEmpty()) return emptyList()
        val cached = marketService.getMarkets(conditionIds)
        return conditionIds.map { id ->
            val market = cached[id]
            if (market != null) {
                WhaleMonitorMarketDto(
                    conditionId = id,
                    title = market.title,
                    slug = market.slug,
                    category = market.category,
                    image = market.image,
                    icon = market.icon
                )
            } else {
                WhaleMonitorMarketDto(conditionId = id, title = id)
            }
        }
    }

    private fun entityToDto(e: WhaleMonitorStrategy, lastTriggerAt: Long?, triggerCount: Long): WhaleMonitorStrategyDto {
        val conditionIdList: List<String> = try {
            e.conditionIds.fromJson<List<String>>() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return WhaleMonitorStrategyDto(
            id = e.id ?: 0L,
            accountId = e.accountId,
            name = e.name,
            conditionIds = conditionIdList,
            markets = buildMarketDtos(conditionIdList),
            windowSeconds = e.windowSeconds,
            thresholdAmount = e.thresholdAmount.toPlainString(),
            orderAmount = e.orderAmount.toPlainString(),
            minPrice = e.minPrice.toPlainString(),
            maxPrice = e.maxPrice.toPlainString(),
            cooldownSeconds = e.cooldownSeconds,
            enabled = e.enabled,
            lastTriggerAt = lastTriggerAt,
            triggerCount = triggerCount,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt
        )
    }

    private fun triggerToDto(t: WhaleMonitorTrigger): WhaleMonitorTriggerDto = WhaleMonitorTriggerDto(
        id = t.id ?: 0L,
        strategyId = t.strategyId,
        conditionId = t.conditionId,
        tokenId = t.tokenId,
        side = t.side,
        triggerVolume = t.triggerVolume.toPlainString(),
        orderPrice = t.orderPrice.toPlainString(),
        orderSize = t.orderSize.toPlainString(),
        orderAmount = t.orderAmount.toPlainString(),
        orderId = t.orderId,
        status = t.status,
        failReason = t.failReason,
        createdAt = t.createdAt
    )
}
