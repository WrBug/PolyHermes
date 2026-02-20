package com.wrbug.polymarketbot.service.binance

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 币安 K 线 WebSocket：按需订阅尾盘策略使用的币种 5m/15m，维护当前周期 (open, close)，供价差校验使用。
 * 仅当存在启用策略且策略使用到某市场时才订阅对应币种，无策略时不建立连接。
 */
@Service
class BinanceKlineService {

    private val logger = LoggerFactory.getLogger(BinanceKlineService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wsBase = "wss://stream.binance.com:9443"
    private val client = createClient().build()

    /** (marketSlugPrefix, intervalSeconds, periodStartUnix) -> (open, close) */
    private val openCloseByPeriod = ConcurrentHashMap<String, Pair<BigDecimal, BigDecimal>>()
    
    /** 市场 slug 前缀（如 btc-updown）-> Binance 交易对映射 */
    private val marketToSymbol = mapOf(
        "btc-updown" to "BTCUSDC",
        "eth-updown" to "ETHUSDC",
        "sol-updown" to "SOLUSDC",
        "xrp-updown" to "XRPUSDC"
    )
    
    /** 已连接的 WebSocket: wsKey (symbol-interval) -> WebSocket */
    private val connectedWebSockets = ConcurrentHashMap<String, WebSocket>()
    /** 当前需要订阅的完整市场集合（如 btc-updown-5m、btc-updown-15m），由尾盘策略刷新时更新 */
    private val requiredMarketPrefixes = AtomicReference<Set<String>>(emptySet())
    private val subscriptionLock = Any()
    private var reconnectJob: Job? = null

    /** 解析完整市场 slug（如 btc-updown-5m）为 (basePrefix, interval)，不支持则返回 null */
    private fun parseMarketSlug(full: String): Pair<String, String>? {
        val lower = full.lowercase()
        return when {
            lower.endsWith("-5m") -> Pair(lower.removeSuffix("-5m"), "5m")
            lower.endsWith("-15m") -> Pair(lower.removeSuffix("-15m"), "15m")
            else -> null
        }
    }

    /** 从市场 base 前缀（如 btc-updown）获取 Binance 交易对 */
    private fun getSymbol(basePrefix: String): String? = marketToSymbol[basePrefix]

    private fun key(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): String {
        return "$marketSlugPrefix-$intervalSeconds-$periodStartUnix"
    }

    fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        return openCloseByPeriod[key(marketSlugPrefix, intervalSeconds, periodStartUnix)]
    }

    /** 供 API 健康检查使用：各币种各周期的连接状态 */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return connectedWebSockets.keys.associateWith { connectedWebSockets[it] != null }
    }

    /**
     * 按需更新订阅：仅订阅策略用到的 (币种, 周期)，例如只开 btc 5min 则只建 btc 5min K 线连接。
     * 由 CryptoTailOrderbookWsService 在刷新订阅时根据启用策略的 marketSlugPrefix 调用。
     * @param marketPrefixes 当前启用策略用到的完整市场集合，如 ["btc-updown-5m"] 或 ["btc-updown-5m", "eth-updown-15m"]；空集合时关闭所有连接
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val normalized = marketPrefixes.map { it.lowercase() }.toSet()
        val parsed = normalized.mapNotNull { full ->
            parseMarketSlug(full)?.let { (base, interval) ->
                getSymbol(base)?.let { symbol -> Triple(full, symbol, interval) }
            }
        }.toSet()
        val wsKeysNeeded = parsed.map { (_, symbol, interval) -> "$symbol-$interval" }.toSet()
        if (normalized == requiredMarketPrefixes.get()) return
        requiredMarketPrefixes.set(normalized)
        synchronized(subscriptionLock) {
            connectedWebSockets.keys.toList().forEach { wsKey ->
                if (wsKey !in wsKeysNeeded) {
                    connectedWebSockets.remove(wsKey)?.close(1000, "subscription_update")
                    logger.info("币安 K 线 WS 已关闭（无策略使用）: $wsKey")
                }
            }
            parsed.forEach { (fullPrefix, symbol, interval) ->
                connectStream(symbol, interval, fullPrefix) { marketPrefixParam, intervalSec, tMs, openP, closeP ->
                    val periodSec = tMs / 1000
                    openCloseByPeriod[key(marketPrefixParam, intervalSec, periodSec)] = openP to closeP
                }
            }
        }
    }

    private fun connectStream(
        symbol: String,
        interval: String,
        marketPrefix: String,
        onKline: (marketPrefix: String, intervalSeconds: Int, openTimeMs: Long, open: BigDecimal, close: BigDecimal) -> Unit
    ) {
        val streamName = "${symbol.lowercase()}@kline_$interval"
        val wsKey = "$symbol-$interval"
        if (connectedWebSockets[wsKey] != null) return
        
        val url = "$wsBase/ws/$streamName"
        val intervalSeconds = when (interval) {
            "5m" -> 300
            "15m" -> 900
            else -> 300
        }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connectedWebSockets[wsKey] = webSocket
                logger.info("币安 K 线 WS 已连接: $streamName")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseKlineMessage(text, intervalSeconds)?.let { (tMs, o, c) ->
                    onKline(marketPrefix, intervalSeconds, tMs, o, c)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                connectedWebSockets.remove(wsKey)
                logger.warn("币安 K 线 WS 异常 $streamName: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(wsKey)
                if (code != 1000) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(wsKey)
            }
        })
    }

    private fun parseKlineMessage(text: String, intervalSeconds: Int): Triple<Long, BigDecimal, BigDecimal>? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            if (json.get("e")?.asString != "kline") return null
            val k = json.getAsJsonObject("k") ?: return null
            val tMs = k.get("t")?.asLong ?: return null
            val o = k.get("o")?.asString?.toSafeBigDecimal() ?: return null
            val c = k.get("c")?.asString?.toSafeBigDecimal() ?: return null
            Triple(tMs, o, c)
        } catch (e: Exception) {
            logger.debug("解析币安 K 线消息失败: ${e.message}")
            null
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            reconnectJob = null
            val current = requiredMarketPrefixes.get()
            connectedWebSockets.values.forEach { it.close(1000, "reconnect") }
            connectedWebSockets.clear()
            logger.info("币安 K 线 WS 尝试重连")
            updateSubscriptions(current)
        }
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        connectedWebSockets.values.forEach { it.close(1000, "shutdown") }
        connectedWebSockets.clear()
    }
}
