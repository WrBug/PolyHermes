package com.wrbug.polymarketbot.controller

import com.wrbug.polymarketbot.api.LatestPriceResponse
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.AccountService
import com.wrbug.polymarketbot.service.PolymarketClobService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 市场数据控制器
 * 提供市场相关的数据查询接口（价格、订单簿等）
 */
@RestController
@RequestMapping("/api/copy-trading/markets")
class MarketController(
    private val accountService: AccountService,
    private val clobService: PolymarketClobService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(MarketController::class.java)
    
    /**
     * 获取市场价格（通过 Gamma API）
     * 使用 Gamma API 获取价格信息，因为 Gamma API 支持 condition_ids 参数
     */
    @PostMapping("/price")
    fun getMarketPrice(@RequestBody request: MarketPriceRequest): ResponseEntity<ApiResponse<MarketPriceResponse>> {
        return try {
            if (request.marketId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
            }
            
            val result = runBlocking { accountService.getMarketPrice(request.marketId, request.outcomeIndex) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取市场价格失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_MARKET_PRICE_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取市场价格异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_MARKET_PRICE_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * 获取最新价（从订单表获取，供前端下单时显示）
     * 支持多元市场（二元、三元及以上）
     * 通过 tokenId 获取特定 outcome 的订单表，返回 bestBid 和 bestAsk
     */
    @PostMapping("/latest-price")
    fun getLatestPrice(@RequestBody request: LatestPriceRequest): ResponseEntity<ApiResponse<LatestPriceResponse>> {
        return try {
            if (request.tokenId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TOKEN_ID_EMPTY, messageSource = messageSource))
            }
            
            val result = runBlocking { clobService.getLatestPrice(request.tokenId) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取最新价失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_MARKET_LATEST_PRICE_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("获取最新价异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_MARKET_LATEST_PRICE_FETCH_FAILED, e.message, messageSource))
        }
    }
}





