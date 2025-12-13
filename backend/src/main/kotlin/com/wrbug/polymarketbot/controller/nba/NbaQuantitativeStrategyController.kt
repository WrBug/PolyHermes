package com.wrbug.polymarketbot.controller.nba

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.nba.NbaQuantitativeStrategyService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * NBA 量化策略控制器
 */
@RestController
@RequestMapping("/api/nba/strategies")
class NbaQuantitativeStrategyController(
    private val strategyService: NbaQuantitativeStrategyService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(NbaQuantitativeStrategyController::class.java)
    
    /**
     * 创建策略
     */
    @PostMapping("/create")
    fun createStrategy(@RequestBody request: NbaQuantitativeStrategyCreateRequest): ResponseEntity<ApiResponse<NbaQuantitativeStrategyDto>> {
        return try {
            if (request.strategyName.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, customMsg = "策略名称不能为空", messageSource = messageSource))
            }
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, customMsg = "账户ID无效", messageSource = messageSource))
            }
            
            val result = runBlocking { strategyService.createStrategy(request) }
            result.fold(
                onSuccess = { strategy ->
                    ResponseEntity.ok(ApiResponse.success(strategy))
                },
                onFailure = { e ->
                    logger.error("创建策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 更新策略
     */
    @PostMapping("/update")
    fun updateStrategy(@RequestBody request: NbaQuantitativeStrategyUpdateRequest): ResponseEntity<ApiResponse<NbaQuantitativeStrategyDto>> {
        return try {
            if (request.id <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, customMsg = "策略ID无效", messageSource = messageSource))
            }
            
            val result = runBlocking { strategyService.updateStrategy(request) }
            result.fold(
                onSuccess = { strategy ->
                    ResponseEntity.ok(ApiResponse.success(strategy))
                },
                onFailure = { e ->
                    logger.error("更新策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("更新策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 获取策略列表
     */
    @PostMapping("/list")
    fun getStrategyList(@RequestBody request: NbaQuantitativeStrategyListRequest): ResponseEntity<ApiResponse<NbaQuantitativeStrategyListResponse>> {
        return try {
            val result = runBlocking { strategyService.getStrategyList(request) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取策略列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取策略列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 获取策略详情
     */
    @PostMapping("/detail")
    fun getStrategyDetail(@RequestBody request: NbaQuantitativeStrategyDetailRequest): ResponseEntity<ApiResponse<NbaQuantitativeStrategyDto>> {
        return try {
            if (request.id == null || request.id <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, customMsg = "策略ID不能为空", messageSource = messageSource))
            }
            
            val result = runBlocking { strategyService.getStrategyDetail(request.id) }
            result.fold(
                onSuccess = { strategy ->
                    ResponseEntity.ok(ApiResponse.success(strategy))
                },
                onFailure = { e ->
                    logger.error("获取策略详情失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取策略详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 删除策略
     */
    @PostMapping("/delete")
    fun deleteStrategy(@RequestBody request: NbaQuantitativeStrategyDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.id == null || request.id <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, customMsg = "策略ID不能为空", messageSource = messageSource))
            }
            
            val result = runBlocking { strategyService.deleteStrategy(request.id) }
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除策略失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除策略异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

/**
 * 策略详情请求
 */
data class NbaQuantitativeStrategyDetailRequest(
    val id: Long?
)

/**
 * 策略删除请求
 */
data class NbaQuantitativeStrategyDeleteRequest(
    val id: Long?
)

