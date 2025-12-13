package com.wrbug.polymarketbot.controller.nba

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.nba.NbaGameService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * NBA 比赛控制器
 */
@RestController
@RequestMapping("/api/nba/games")
class NbaGameController(
    private val nbaGameService: NbaGameService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(NbaGameController::class.java)
    
    /**
     * 获取 NBA 比赛列表
     */
    @PostMapping("/list")
    fun getNbaGames(@RequestBody request: NbaGameListRequest): ResponseEntity<ApiResponse<NbaGameListResponse>> {
        return try {
            val result = runBlocking {
                nbaGameService.getNbaGames(request)
            }
            
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("获取 NBA 比赛列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取 NBA 比赛列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
    
    /**
     * 获取 7 天内的所有球队（用于策略配置）
     */
    @PostMapping("/teams")
    fun getTeamsInNext7Days(): ResponseEntity<ApiResponse<List<String>>> {
        return try {
            val result = runBlocking {
                nbaGameService.getTeamsInNext7Days()
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

