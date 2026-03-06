package com.wrbug.polymarketbot.service.sportstail

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.entity.SportsTailStrategy
import com.wrbug.polymarketbot.event.SportsTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.SportsTailStrategyRepository
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gte
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lte
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 体育尾盘策略订单簿 WebSocket 服务：订阅 CLOB 市场频道，价格达到触发价时执行买入/止盈止损卖出。
 */
@Service
class SportsTailOrderbookWsService(
    private val strategyRepository: SportsTailStrategyRepository,
    private val executionService: SportsTailStrategyExecutionService
) {

    private val logger = LoggerFactory.getLogger(SportsTailOrderbookWsService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)

    /** tokenId -> list of (strategy, outcomeIndex for buy=0/1, isSellPhase) */
    private val tokenToEntries = AtomicReference<Map<String, List<WsEntry>>>(emptyMap())

    private var webSocket: WebSocket? = null
    private val wsUrl = PolymarketConstants.RTDS_WS_URL + "/ws/market"
    private val client: OkHttpClient by lazy { createClient().build() }

    private val reconnectDelayMs = 3_000L
    private val closedForNoStrategies = AtomicBoolean(false)
    private val connectLock = Any()
    private val refreshLock = Any()
    private val isRefreshing = AtomicBoolean(false)

    private data class WsEntry(
        val strategy: SportsTailStrategy,
        val outcomeIndex: Int,
        val isSellPhase: Boolean
    )

    private var reconnectJob: Job? = null

    @PostConstruct
    fun init() {
        if (hasActiveStrategies()) connect()
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        reconnectJob = null
        closedForNoStrategies.set(true)
        try {
            webSocket?.close(1000, "shutdown")
        } catch (e: Exception) {
            logger.debug("关闭体育尾盘 WebSocket 时异常: ${e.message}")
        }
        webSocket = null
        scopeJob.cancel()
    }

    private fun hasActiveStrategies(): Boolean {
        val all = strategyRepository.findAll()
        return all.any { !it.filled || (it.filled && !it.sold && (it.takeProfitPrice != null || it.stopLossPrice != null)) }
    }

    private fun connect() {
        synchronized(connectLock) {
            if (webSocket != null) return
            try {
                val request = Request.Builder().url(wsUrl).build()
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        logger.info("体育尾盘策略订单簿 WebSocket 已连接")
                        refreshAndSubscribe(fromConnect = true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        this@SportsTailOrderbookWsService.webSocket = null
                        if (!closedForNoStrategies.getAndSet(false)) scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        logger.warn("体育尾盘策略订单簿 WebSocket 异常: ${t.message}")
                        this@SportsTailOrderbookWsService.webSocket = null
                        scheduleReconnect()
                    }
                })
            } catch (e: Exception) {
                logger.error("体育尾盘策略订单簿 WebSocket 连接失败: ${e.message}", e)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            if (!hasActiveStrategies()) return@launch
            logger.info("体育尾盘策略订单簿 WebSocket 尝试重连")
            connect()
        }
    }

    private fun handleMessage(text: String) {
        if (text == "pong" || text.isEmpty()) return
        if (closedForNoStrategies.get()) return
        val json = text.fromJson<JsonObject>() ?: return
        val eventType = (json.get("event_type") as? JsonPrimitive)?.asString ?: return

        when (eventType) {
            "book" -> {
                val assetId = (json.get("asset_id") as? JsonPrimitive)?.asString ?: return
                val bids = json.get("bids") as? JsonArray
                if (bids == null || bids.isEmpty) return
                var bestBid: BigDecimal? = null
                for (i in 0 until bids.size()) {
                    val level = bids.get(i) as? JsonObject ?: continue
                    val p = (level.get("price") as? JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                    if (bestBid == null || p.gt(bestBid)) bestBid = p
                }
                if (bestBid != null) onPriceUpdate(assetId, bestBid)
            }
            "price_change" -> {
                val priceChanges = json.get("price_changes") as? JsonArray ?: return
                for (i in 0 until priceChanges.size()) {
                    val pc = priceChanges.get(i) as? JsonObject ?: continue
                    val assetId = (pc.get("asset_id") as? JsonPrimitive)?.asString ?: continue
                    val bestBidStr = (pc.get("best_bid") as? JsonPrimitive)?.asString
                    val bestBid = bestBidStr?.toSafeBigDecimal()
                    if (bestBid != null) onPriceUpdate(assetId, bestBid)
                }
            }
        }
    }

    private fun onPriceUpdate(tokenId: String, bestBid: BigDecimal) {
        if (closedForNoStrategies.get()) return
        val entries = tokenToEntries.get()[tokenId] ?: return
        for (e in entries) {
            scope.launch {
                try {
                    if (e.isSellPhase) {
                        checkSellTrigger(e.strategy, bestBid)
                    } else {
                        checkBuyTrigger(e.strategy, e.outcomeIndex, bestBid)
                    }
                } catch (ex: Exception) {
                    logger.error("体育尾盘 WS 处理异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                }
            }
        }
    }

    private suspend fun checkBuyTrigger(strategy: SportsTailStrategy, outcomeIndex: Int, price: BigDecimal) {
        if (strategy.filled) return
        if (price.gte(strategy.triggerPrice)) {
            executionService.executeBuy(strategy, outcomeIndex, price)
        }
    }

    private suspend fun checkSellTrigger(strategy: SportsTailStrategy, currentPrice: BigDecimal) {
        if (!strategy.filled || strategy.sold) return
        strategy.takeProfitPrice?.let { if (currentPrice.gte(it)) { executionService.executeSell(strategy, "TAKE_PROFIT", currentPrice); return } }
        strategy.stopLossPrice?.let { if (currentPrice.lte(it)) { executionService.executeSell(strategy, "STOP_LOSS", currentPrice); return } }
    }

    private fun refreshAndSubscribe(fromConnect: Boolean = false) {
        synchronized(refreshLock) {
            if (isRefreshing.get()) return
            isRefreshing.set(true)
        }
        try {
            val strategies = strategyRepository.findAll()
            val active = strategies.filter { s ->
                !s.filled || (s.filled && !s.sold && (s.takeProfitPrice != null || s.stopLossPrice != null))
            }
            val tokenIdSet = mutableSetOf<String>()
            val map = mutableMapOf<String, MutableList<WsEntry>>()

            for (s in active) {
                if (!s.filled) {
                    s.yesTokenId?.let { id ->
                        if (id.isNotBlank()) {
                            tokenIdSet.add(id)
                            map.getOrPut(id) { mutableListOf() }.add(WsEntry(s, 0, false))
                        }
                    }
                    s.noTokenId?.let { id ->
                        if (id.isNotBlank()) {
                            tokenIdSet.add(id)
                            map.getOrPut(id) { mutableListOf() }.add(WsEntry(s, 1, false))
                        }
                    }
                } else if (!s.sold && (s.takeProfitPrice != null || s.stopLossPrice != null)) {
                    val idx = s.filledOutcomeIndex ?: continue
                    val tokenId = if (idx == 0) s.yesTokenId else s.noTokenId
                    tokenId?.takeIf { it.isNotBlank() }?.let { id ->
                        tokenIdSet.add(id)
                        map.getOrPut(id) { mutableListOf() }.add(WsEntry(s, idx, true))
                    }
                }
            }

            tokenToEntries.set(map)

            if (tokenIdSet.isEmpty()) {
                closeForNoStrategies()
                return
            }
            if (!fromConnect) {
                if (webSocket == null) {
                    connect()
                    return
                }
                closeAndReconnect()
                return
            }
            val msg = """{"type":"MARKET","assets_ids":${tokenIdSet.toList().toJson()}}"""
            try {
                webSocket?.send(msg)
                logger.info("体育尾盘策略订单簿订阅: ${tokenIdSet.size} 个 token")
            } catch (e: Exception) {
                logger.warn("发送体育尾盘订阅失败: ${e.message}")
            }
        } finally {
            isRefreshing.set(false)
        }
    }

    private fun closeAndReconnect() {
        val ws = webSocket
        if (ws != null) {
            webSocket = null
            try { ws.close(1000, "subscription_change") } catch (e: Exception) { }
            logger.info("体育尾盘策略订单簿 WebSocket 已关闭（订阅更新，将重连）")
        }
    }

    private fun closeForNoStrategies() {
        reconnectJob?.cancel()
        reconnectJob = null
        val ws = webSocket
        if (ws != null) {
            closedForNoStrategies.set(true)
            webSocket = null
            try { ws.close(1000, "no_active_strategies") } catch (e: Exception) { }
            logger.info("体育尾盘策略订单簿 WebSocket 已关闭（无活跃策略）")
        }
    }

    @EventListener
    fun onStrategyChanged(event: SportsTailStrategyChangedEvent) {
        refreshAndSubscribe()
    }
}
