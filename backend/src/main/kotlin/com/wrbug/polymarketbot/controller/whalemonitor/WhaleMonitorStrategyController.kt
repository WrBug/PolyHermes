package com.wrbug.polymarketbot.controller.whalemonitor

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.whalemonitor.WhaleMonitorStrategyService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/whale-monitor-strategy")
class WhaleMonitorStrategyController(
    private val whaleMonitorStrategyService: WhaleMonitorStrategyService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(WhaleMonitorStrategyController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: WhaleMonitorStrategyListRequest): ResponseEntity<ApiResponse<WhaleMonitorStrategyListResponse>> {
        return try {
            val result = whaleMonitorStrategyService.list(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询大单监听策略列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询大单监听策略列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/create")
    fun create(@RequestBody request: WhaleMonitorStrategyCreateRequest): ResponseEntity<ApiResponse<WhaleMonitorStrategyDto>> {
        return try {
            val result = whaleMonitorStrategyService.create(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("创建大单监听策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY
                        ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID
                        ErrorCode.PARAM_ACCOUNT_ID_INVALID.messageKey -> ErrorCode.PARAM_ACCOUNT_ID_INVALID
                        else -> ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_CREATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建大单监听策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_CREATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update")
    fun update(@RequestBody request: WhaleMonitorStrategyUpdateRequest): ResponseEntity<ApiResponse<WhaleMonitorStrategyDto>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = whaleMonitorStrategyService.update(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("更新大单监听策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND
                        ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_CONDITION_IDS_EMPTY
                        ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_WINDOW_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_THRESHOLD_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_AMOUNT_INVALID
                        ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID.messageKey -> ErrorCode.WHALE_MONITOR_STRATEGY_PRICE_INVALID
                        else -> ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_UPDATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, messageSource = messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("更新大单监听策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_UPDATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/delete")
    fun delete(@RequestBody request: WhaleMonitorStrategyDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val strategyId = request.strategyId
            if (strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = whaleMonitorStrategyService.delete(strategyId)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
                onFailure = { e ->
                    logger.error("删除大单监听策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_DELETE_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除大单监听策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/triggers")
    fun getTriggerRecords(@RequestBody request: WhaleMonitorTriggerListRequest): ResponseEntity<ApiResponse<WhaleMonitorTriggerListResponse>> {
        return try {
            if (request.strategyId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.WHALE_MONITOR_STRATEGY_NOT_FOUND, messageSource = messageSource))
            }
            val result = whaleMonitorStrategyService.getTriggerRecords(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询大单监听触发记录失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询大单监听触发记录异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_WHALE_MONITOR_STRATEGY_TRIGGERS_FETCH_FAILED, e.message, messageSource))
        }
    }
}
