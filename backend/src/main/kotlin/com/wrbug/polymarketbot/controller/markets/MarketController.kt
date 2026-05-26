package com.wrbug.polymarketbot.controller.markets

import com.wrbug.polymarketbot.api.LatestPriceResponse
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.common.MarketSearchResult
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.SportCategoryResult
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 市场数据控制器
 * 提供市场相关的数据查询接口（价格、订单簿等）
 */
@RestController
@RequestMapping("/api/markets")
class MarketController(
    private val accountService: AccountService,
    private val clobService: PolymarketClobService,
    private val marketPriceService: MarketPriceService,
    private val marketService: MarketService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(MarketController::class.java)
    
    /**
     * 获取市场价格
     * 使用 MarketPriceService 获取当前市场价格（支持多数据源降级）
     * 返回当前价格，前端接收后自行填充到 bestBid 字段
     */
    @PostMapping("/price")
    fun getMarketPrice(@RequestBody request: MarketPriceRequest): ResponseEntity<ApiResponse<MarketPriceResponse>> {
        return try {
            if (request.marketId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
            }
            
            val outcomeIndex = request.outcomeIndex ?: 0
            val price = runBlocking { 
                marketPriceService.getCurrentMarketPrice(request.marketId, outcomeIndex) 
            }
            
            val response = MarketPriceResponse(
                marketId = request.marketId,
                currentPrice = price.toString()
            )
            ResponseEntity.ok(ApiResponse.success(response))
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

    /**
     * 搜索市场（按标题关键词和/或分类标签，调用 Gamma API）
     */
    @PostMapping("/search")
    fun searchMarkets(@RequestBody request: Map<String, Any>): ResponseEntity<ApiResponse<List<MarketSearchResult>>> {
        return try {
            val keyword = (request["keyword"] as? String)?.trim() ?: ""
            val tagId = request["tagId"] as? String
            val seriesId = request["seriesId"] as? String
            val sportSlug = request["sportSlug"] as? String
            val limit = (request["limit"] as? Number)?.toInt() ?: 50
            if (keyword.length < 2 && tagId.isNullOrBlank() && seriesId.isNullOrBlank() && sportSlug.isNullOrBlank()) {
                return ResponseEntity.ok(ApiResponse.success(emptyList()))
            }
            val results = runBlocking { marketService.searchMarkets(keyword, tagId, seriesId, sportSlug, limit) }
            ResponseEntity.ok(ApiResponse.success(results))
        } catch (e: Exception) {
            logger.error("搜索市场异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 获取体育联赛子分类列表（NBA、MLB、EPL 等）
     */
    @PostMapping("/sports-categories")
    fun getSportsCategories(): ResponseEntity<ApiResponse<List<SportCategoryResult>>> {
        return try {
            val results = runBlocking { marketService.listSportsCategories() }
            ResponseEntity.ok(ApiResponse.success(results))
        } catch (e: Exception) {
            logger.error("获取体育分类异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}





