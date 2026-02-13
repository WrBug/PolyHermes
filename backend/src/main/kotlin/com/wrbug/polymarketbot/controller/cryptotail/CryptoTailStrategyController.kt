package com.wrbug.polymarketbot.controller.cryptotail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyCreateRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyDeleteRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyDto
import com.wrbug.polymarketbot.dto.CryptoTailStrategyListRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyListResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyTriggerListRequest
import com.wrbug.polymarketbot.dto.CryptoTailStrategyTriggerListResponse
import com.wrbug.polymarketbot.dto.CryptoTailStrategyUpdateRequest
import com.wrbug.polymarketbot.dto.CryptoTailMarketOptionDto
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailStrategyService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/crypto-tail-strategy")
class CryptoTailStrategyController(
    private val cryptoTailStrategyService: CryptoTailStrategyService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: CryptoTailStrategyListRequest): ResponseEntity<ApiResponse<CryptoTailStrategyListResponse>> {
        return try {
            val result = cryptoTailStrategyService.list(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询尾盘策略列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询尾盘策略列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/create")
    fun create(@RequestBody request: CryptoTailStrategyCreateRequest): ResponseEntity<ApiResponse<CryptoTailStrategyDto>> {
        return try {
            val result = cryptoTailStrategyService.create(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("创建尾盘策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED
                        ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_INTERVAL_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID
                        else -> ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_CREATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建尾盘策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_CREATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update")
    fun update(@RequestBody request: CryptoTailStrategyUpdateRequest): ResponseEntity<ApiResponse<CryptoTailStrategyDto>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.update(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("更新尾盘策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_INVALID
                        ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_WINDOW_EXCEED
                        ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey -> ErrorCode.CRYPTO_TAIL_STRATEGY_AMOUNT_MODE_INVALID
                        else -> ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_UPDATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("更新尾盘策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_UPDATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/delete")
    fun delete(@RequestBody request: CryptoTailStrategyDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val strategyId = request.strategyId
            if (strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.delete(strategyId)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
                onFailure = { e ->
                    logger.error("删除尾盘策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DELETE_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除尾盘策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/triggers")
    fun getTriggerRecords(@RequestBody request: CryptoTailStrategyTriggerListRequest): ResponseEntity<ApiResponse<CryptoTailStrategyTriggerListResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.CRYPTO_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = cryptoTailStrategyService.getTriggerRecords(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询触发记录失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询触发记录异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_CRYPTO_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/market-options")
    fun getMarketOptions(): ResponseEntity<ApiResponse<List<CryptoTailMarketOptionDto>>> {
        return try {
            val options = listOf(
                CryptoTailMarketOptionDto(slug = "btc-updown-5m", title = "Bitcoin Up or Down - 5 minute", intervalSeconds = 300, periodStartUnix = 0L, endDate = null),
                CryptoTailMarketOptionDto(slug = "btc-updown-15m", title = "Bitcoin Up or Down - 15 minute", intervalSeconds = 900, periodStartUnix = 0L, endDate = null)
            )
            ResponseEntity.ok(ApiResponse.success(options))
        } catch (e: Exception) {
            logger.error("获取市场选项异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}
