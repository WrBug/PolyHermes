package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * 尾盘策略订单簿 WebSocket 监听：订阅 CLOB Market 频道，收到订单簿/价格变更时若满足条件立即触发下单。
 */
@Service
class CryptoTailOrderbookWsService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val executionService: CryptoTailStrategyExecutionService,
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderbookWsService::class.java)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** tokenId -> list of (strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex) */
    private val tokenToEntries = AtomicReference<Map<String, List<WsBookEntry>>>(emptyMap())

    private var webSocket: WebSocket? = null
    private val wsUrl = PolymarketConstants.RTDS_WS_URL + "/ws/market"
    private val client = createClient().build()

    /** 订阅成功后设置的倒计时 Job，在周期结束时自动刷新订阅 */
    private var periodEndCountdownJob: Job? = null

    /** 重连延迟（毫秒） */
    private val reconnectDelayMs = 10_000L

    data class WsBookEntry(
        val strategy: CryptoTailStrategy,
        val periodStartUnix: Long,
        val marketTitle: String?,
        val tokenIds: List<String>,
        val outcomeIndex: Int
    )

    @PostConstruct
    fun init() {
        connect()
    }

    private fun connect() {
        if (webSocket != null) return
        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    logger.info("尾盘策略订单簿 WebSocket 已连接")
                    refreshAndSubscribe()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    this@CryptoTailOrderbookWsService.webSocket = null
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    logger.warn("尾盘策略订单簿 WebSocket 异常: ${t.message}")
                    this@CryptoTailOrderbookWsService.webSocket = null
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            logger.error("尾盘策略订单簿 WebSocket 连接失败: ${e.message}", e)
            scheduleReconnect()
        }
    }

    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            logger.info("尾盘策略订单簿 WebSocket 尝试重连")
            connect()
        }
    }

    private fun handleMessage(text: String) {
        if (text == "pong" || text.isEmpty()) return
        maybeRefreshSubscriptionIfPeriodChanged()
        val json = text.fromJson<com.google.gson.JsonObject>() ?: return
        val eventType = (json.get("event_type") as? com.google.gson.JsonPrimitive)?.asString ?: return

        when (eventType) {
            "book" -> {
                val assetId = (json.get("asset_id") as? com.google.gson.JsonPrimitive)?.asString ?: return
                val bids = json.get("bids") as? com.google.gson.JsonArray
                val firstBid = bids?.get(0) as? com.google.gson.JsonObject
                val bestBid = (firstBid?.get("price") as? com.google.gson.JsonPrimitive)?.asString?.toSafeBigDecimal()
                if (bestBid != null) onBestBid(assetId, bestBid)
            }
            "price_change" -> {
                val priceChanges = json.get("price_changes") as? com.google.gson.JsonArray ?: return
                for (i in 0 until priceChanges.size()) {
                    val pc = priceChanges.get(i) as? com.google.gson.JsonObject ?: continue
                    val assetId = (pc.get("asset_id") as? com.google.gson.JsonPrimitive)?.asString ?: continue
                    val bestBidStr = (pc.get("best_bid") as? com.google.gson.JsonPrimitive)?.asString
                    val bestBid = bestBidStr?.toSafeBigDecimal()
                    if (bestBid != null) onBestBid(assetId, bestBid)
                }
            }
        }
    }

    private fun onBestBid(tokenId: String, bestBid: BigDecimal) {
        val entries = tokenToEntries.get()[tokenId] ?: return
        val nowSeconds = System.currentTimeMillis() / 1000
        for (e in entries) {
            val windowStart = e.periodStartUnix + e.strategy.windowStartSeconds
            val windowEnd = e.periodStartUnix + e.strategy.windowEndSeconds
            if (nowSeconds < windowStart || nowSeconds >= windowEnd) continue
            scope.launch {
                try {
                    runBlocking {
                        executionService.tryTriggerWithPriceFromWs(
                            strategy = e.strategy,
                            periodStartUnix = e.periodStartUnix,
                            marketTitle = e.marketTitle,
                            tokenIds = e.tokenIds,
                            outcomeIndex = e.outcomeIndex,
                            bestBid = bestBid
                        )
                    }
                } catch (ex: Exception) {
                    logger.error("WS 触发下单异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * 事件驱动：仅在收到 WS 消息时检查当前周期是否变化，若变化则刷新订阅，无需定时轮询。
     */
    private fun maybeRefreshSubscriptionIfPeriodChanged() {
        val subscribed = tokenToEntries.get().values.flatten().distinctBy { it.strategy.id }.associate { it.strategy.id!! to it.periodStartUnix }
        if (subscribed.isEmpty()) return
        val strategies = strategyRepository.findAllByEnabledTrue()
        val nowSeconds = System.currentTimeMillis() / 1000
        val currentStrategyIds = strategies.map { it.id!! }.toSet()
        if (subscribed.keys != currentStrategyIds) {
            refreshAndSubscribe()
            return
        }
        for (s in strategies) {
            val currentPeriod = (nowSeconds / s.intervalSeconds) * s.intervalSeconds
            val subPeriod = subscribed[s.id!!] ?: continue
            if (currentPeriod != subPeriod) {
                refreshAndSubscribe()
                return
            }
        }
    }

    private fun refreshAndSubscribe() {
        periodEndCountdownJob?.cancel()
        periodEndCountdownJob = null
        val (tokenIds, newMap) = buildSubscriptionMap()
        tokenToEntries.set(newMap)
        if (tokenIds.isEmpty()) return
        val marketSlugs = newMap.values.asSequence().flatten()
            .distinctBy { "${it.strategy.marketSlugPrefix}-${it.periodStartUnix}" }
            .map { "${it.strategy.marketSlugPrefix}-${it.periodStartUnix}" }
            .toList()
        val msg = """{"type":"MARKET","assets_ids":${tokenIds.toJson()}}"""
        try {
            webSocket?.send(msg)
            logger.info("尾盘策略订单簿订阅: ${tokenIds.size} 个 token, 市场: $marketSlugs")
        } catch (e: Exception) {
            logger.warn("发送订阅失败: ${e.message}")
            return
        }
        scheduleRefreshAtPeriodEnd(newMap)
    }

    /**
     * 订阅成功后设置倒计时：在当前周期结束时自动刷新订阅，无需等消息触发。
     */
    private fun scheduleRefreshAtPeriodEnd(newMap: Map<String, List<WsBookEntry>>) {
        val entries = newMap.values.flatten()
        if (entries.isEmpty()) return
        val nextPeriodEndSeconds = entries.minOf { it.periodStartUnix + it.strategy.intervalSeconds }
        val delayMs = (nextPeriodEndSeconds * 1000) - System.currentTimeMillis() + 2000
        if (delayMs <= 0) return
        periodEndCountdownJob = scope.launch {
            delay(delayMs)
            periodEndCountdownJob = null
            refreshAndSubscribe()
        }
        logger.debug("尾盘策略订单簿订阅倒计时: ${delayMs / 1000}s 后刷新")
    }

    private fun buildSubscriptionMap(): Pair<List<String>, Map<String, List<WsBookEntry>>> {
        val strategies = strategyRepository.findAllByEnabledTrue()
        val nowSeconds = System.currentTimeMillis() / 1000
        val tokenIdSet = mutableSetOf<String>()
        val map = mutableMapOf<String, MutableList<WsBookEntry>>()

        for (strategy in strategies) {
            val interval = strategy.intervalSeconds
            val periodStartUnix = (nowSeconds / interval) * interval
            val windowEnd = periodStartUnix + strategy.windowEndSeconds
            if (nowSeconds >= windowEnd) continue
            val slug = "${strategy.marketSlugPrefix}-$periodStartUnix"
            val event = fetchEventBySlug(slug).getOrNull() ?: continue
            val market = event.markets?.firstOrNull() ?: continue
            val tokenIds = parseClobTokenIds(market.clobTokenIds)
            if (tokenIds.size < 2) continue
            tokenIdSet.addAll(tokenIds)
            for (i in tokenIds.indices) {
                map.getOrPut(tokenIds[i]) { mutableListOf() }.add(
                    WsBookEntry(strategy, periodStartUnix, event.title, tokenIds, i)
                )
            }
        }

        return Pair(tokenIdSet.toList(), map)
    }

    private fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val api = retrofitFactory.createGammaApi()
            val response = runBlocking { api.getEventBySlug(slug) }
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        val parsed = clobTokenIds.fromJson<List<String>>()
        return parsed ?: emptyList()
    }

    @EventListener
    fun onStrategyChanged(event: CryptoTailStrategyChangedEvent) {
        refreshAndSubscribe()
    }
}
