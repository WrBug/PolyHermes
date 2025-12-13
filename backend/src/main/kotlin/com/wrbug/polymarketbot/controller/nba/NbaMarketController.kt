package com.wrbug.polymarketbot.controller.nba

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.nba.NbaMarketService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * NBA 市场控制器
 */
@RestController
@RequestMapping("/api/nba/markets")
class NbaMarketController(
    private val nbaMarketService: NbaMarketService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(NbaMarketController::class.java)
    
    /**
     * 获取 NBA 市场列表
     */
    @PostMapping("/list")
    fun getNbaMarkets(@RequestBody request: NbaMarketListRequest): ResponseEntity<ApiResponse<NbaMarketListResponse>> {
        return try {
            val result = runBlocking {
                nbaMarketService.getNbaMarkets(request)
            }
            
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("获取 NBA 市场列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取 NBA 市场列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 从 NBA 市场中获取球队列表（用于策略配置）
     * 从市场名称中解析出所有唯一的球队名称
     */
    @PostMapping("/teams")
    fun getTeamsFromMarkets(): ResponseEntity<ApiResponse<List<String>>> {
        return try {
            val result = runBlocking {
                nbaMarketService.getTeamsFromMarkets(active = true)
            }
            
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("获取球队列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取球队列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

