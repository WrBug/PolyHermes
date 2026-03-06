package com.wrbug.polymarketbot.controller.sportstail

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.SportsCategoryListResponse
import com.wrbug.polymarketbot.dto.SportsMarketDetailRequest
import com.wrbug.polymarketbot.dto.SportsMarketDetailResponse
import com.wrbug.polymarketbot.dto.SportsMarketSearchRequest
import com.wrbug.polymarketbot.dto.SportsMarketSearchResponse
import com.wrbug.polymarketbot.dto.SportsTailStrategyCreateRequest
import com.wrbug.polymarketbot.dto.SportsTailStrategyCreateResponse
import com.wrbug.polymarketbot.dto.SportsTailStrategyDeleteRequest
import com.wrbug.polymarketbot.dto.SportsTailStrategyListRequest
import com.wrbug.polymarketbot.dto.SportsTailStrategyListResponse
import com.wrbug.polymarketbot.dto.SportsTailTriggerListRequest
import com.wrbug.polymarketbot.dto.SportsTailTriggerListResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.sportstail.SportsTailStrategyService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sports-tail-strategy")
class SportsTailStrategyController(
    private val sportsTailStrategyService: SportsTailStrategyService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(SportsTailStrategyController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: SportsTailStrategyListRequest): ResponseEntity<ApiResponse<SportsTailStrategyListResponse>> {
        return try {
            val result = sportsTailStrategyService.list(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询体育尾盘策略列表失败: ${e.message}", e)
                    ResponseEntity.ok(
                        ApiResponse.error(
                            ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_LIST_FETCH_FAILED,
                            e.message,
                            messageSource
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("查询体育尾盘策略列表异常: ${e.message}", e)
            ResponseEntity.ok(
                ApiResponse.error(
                    ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_LIST_FETCH_FAILED,
                    e.message,
                    messageSource
                )
            )
        }
    }

    @PostMapping("/create")
    fun create(@RequestBody request: SportsTailStrategyCreateRequest): ResponseEntity<ApiResponse<SportsTailStrategyCreateResponse>> {
        return try {
            val result = sportsTailStrategyService.create(request)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(
                        ApiResponse.success(SportsTailStrategyCreateResponse(id = it.id))
                    )
                },
                onFailure = { e ->
                    logger.error("创建体育尾盘策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.ACCOUNT_NOT_FOUND.messageKey -> ErrorCode.ACCOUNT_NOT_FOUND
                        ErrorCode.SPORTS_TAIL_STRATEGY_CONDITION_ID_EMPTY.messageKey -> ErrorCode.SPORTS_TAIL_STRATEGY_CONDITION_ID_EMPTY
                        ErrorCode.SPORTS_TAIL_STRATEGY_PRICE_INVALID.messageKey -> ErrorCode.SPORTS_TAIL_STRATEGY_PRICE_INVALID
                        ErrorCode.SPORTS_TAIL_STRATEGY_AMOUNT_MODE_INVALID.messageKey -> ErrorCode.SPORTS_TAIL_STRATEGY_AMOUNT_MODE_INVALID
                        "该市场已存在策略" -> ErrorCode.PARAM_ERROR
                        else -> ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_CREATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建体育尾盘策略异常: ${e.message}", e)
            ResponseEntity.ok(
                ApiResponse.error(
                    ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_CREATE_FAILED,
                    e.message,
                    messageSource
                )
            )
        }
    }

    @PostMapping("/delete")
    fun delete(@RequestBody request: SportsTailStrategyDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val id = request.id
            if (id <= 0) {
                return ResponseEntity.ok(
                    ApiResponse.error(ErrorCode.SPORTS_TAIL_STRATEGY_NOT_FOUND, messageSource = messageSource)
                )
            }
            val result = sportsTailStrategyService.delete(id)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
                onFailure = { e ->
                    logger.error("删除体育尾盘策略失败: ${e.message}", e)
                    val code = when (e.message) {
                        ErrorCode.SPORTS_TAIL_STRATEGY_NOT_FOUND.messageKey -> ErrorCode.SPORTS_TAIL_STRATEGY_NOT_FOUND
                        "已成交未卖出的策略不能删除" -> ErrorCode.PARAM_ERROR
                        else -> ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_DELETE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(code, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除体育尾盘策略异常: ${e.message}", e)
            ResponseEntity.ok(
                ApiResponse.error(
                    ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_DELETE_FAILED,
                    e.message,
                    messageSource
                )
            )
        }
    }

    @PostMapping("/triggers")
    fun triggers(@RequestBody request: SportsTailTriggerListRequest): ResponseEntity<ApiResponse<SportsTailTriggerListResponse>> {
        return try {
            val result = sportsTailStrategyService.getTriggers(request)
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询触发记录失败: ${e.message}", e)
                    ResponseEntity.ok(
                        ApiResponse.error(
                            ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED,
                            e.message,
                            messageSource
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("查询触发记录异常: ${e.message}", e)
            ResponseEntity.ok(
                ApiResponse.error(
                    ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_TRIGGERS_FETCH_FAILED,
                    e.message,
                    messageSource
                )
            )
        }
    }

    @PostMapping("/sports-list")
    fun sportsList(): ResponseEntity<ApiResponse<SportsCategoryListResponse>> {
        return runBlocking {
            try {
                val result = sportsTailStrategyService.getSportsCategories()
                result.fold(
                    onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                    onFailure = { e ->
                        logger.error("查询体育类别失败: ${e.message}", e)
                        ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_SPORTS_FETCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                )
            } catch (e: Exception) {
                logger.error("查询体育类别异常: ${e.message}", e)
                ResponseEntity.ok(
                    ApiResponse.error(
                        ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_SPORTS_FETCH_FAILED,
                        e.message,
                        messageSource
                    )
                )
            }
        }
    }

    @PostMapping("/market-search")
    fun marketSearch(@RequestBody request: SportsMarketSearchRequest): ResponseEntity<ApiResponse<SportsMarketSearchResponse>> {
        return runBlocking {
            try {
                val result = sportsTailStrategyService.searchMarkets(request)
                result.fold(
                    onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                    onFailure = { e ->
                        logger.error("搜索市场失败: ${e.message}", e)
                        ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_MARKET_SEARCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                )
            } catch (e: Exception) {
                logger.error("搜索市场异常: ${e.message}", e)
                ResponseEntity.ok(
                    ApiResponse.error(
                        ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_MARKET_SEARCH_FAILED,
                        e.message,
                        messageSource
                    )
                )
            }
        }
    }

    @PostMapping("/market-detail")
    fun marketDetail(@RequestBody request: SportsMarketDetailRequest): ResponseEntity<ApiResponse<SportsMarketDetailResponse>> {
        return runBlocking {
            try {
                if (request.conditionId.isBlank()) {
                    return@runBlocking ResponseEntity.ok(
                        ApiResponse.error(ErrorCode.SPORTS_TAIL_STRATEGY_CONDITION_ID_EMPTY, messageSource = messageSource)
                    )
                }
                val result = sportsTailStrategyService.getMarketDetail(request.conditionId)
                result.fold(
                    onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                    onFailure = { e ->
                        logger.error("获取市场详情失败: ${e.message}", e)
                        ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_MARKET_DETAIL_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                )
            } catch (e: Exception) {
                logger.error("获取市场详情异常: ${e.message}", e)
                ResponseEntity.ok(
                    ApiResponse.error(
                        ErrorCode.SERVER_SPORTS_TAIL_STRATEGY_MARKET_DETAIL_FAILED,
                        e.message,
                        messageSource
                    )
                )
            }
        }
    }
}
