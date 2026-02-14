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

/**
 * 币安 K 线 WebSocket：订阅 BTCUSDC 5m/15m，维护当前周期 (open, close)，供尾盘策略价差校验使用。
 */
@Service
class BinanceKlineService {

    private val logger = LoggerFactory.getLogger(BinanceKlineService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wsBase = "wss://stream.binance.com:9443"
    private val client = createClient().build()

    /** (intervalSeconds, periodStartUnix) -> (open, close) */
    private val openCloseByPeriod = ConcurrentHashMap<String, Pair<BigDecimal, BigDecimal>>()
    private var ws5m: WebSocket? = null
    private var ws15m: WebSocket? = null
    private var reconnectJob: Job? = null

    init {
        connectAll()
    }

    private fun key(intervalSeconds: Int, periodStartUnix: Long): String = "$intervalSeconds-$periodStartUnix"

    fun getCurrentOpenClose(intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        return openCloseByPeriod[key(intervalSeconds, periodStartUnix)]
    }

    private fun connectAll() {
        if (ws5m != null && ws15m != null) return
        connectStream("btcusdc@kline_5m") { intervalSec, tMs, openP, closeP ->
            val periodSec = tMs / 1000
            openCloseByPeriod[key(intervalSec, periodSec)] = openP to closeP
        }.also { ws5m = it }
        connectStream("btcusdc@kline_15m") { intervalSec, tMs, openP, closeP ->
            val periodSec = tMs / 1000
            openCloseByPeriod[key(intervalSec, periodSec)] = openP to closeP
        }.also { ws15m = it }
    }

    private fun connectStream(
        streamName: String,
        onKline: (intervalSeconds: Int, openTimeMs: Long, open: BigDecimal, close: BigDecimal) -> Unit
    ): WebSocket {
        val url = "$wsBase/ws/$streamName"
        val intervalSeconds = when {
            streamName.contains("kline_5m") -> 300
            streamName.contains("kline_15m") -> 900
            else -> 300
        }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseKlineMessage(text, intervalSeconds)?.let { (tMs, o, c) ->
                    onKline(intervalSeconds, tMs, o, c)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                logger.warn("币安 K 线 WS 异常 $streamName: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) scheduleReconnect()
            }
        })
        logger.info("币安 K 线 WS 已连接: $streamName")
        return ws
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
            delay(10_000)
            reconnectJob = null
            ws5m?.close(1000, "reconnect")
            ws15m?.close(1000, "reconnect")
            ws5m = null
            ws15m = null
            logger.info("币安 K 线 WS 尝试重连")
            connectAll()
        }
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        ws5m?.close(1000, "shutdown")
        ws15m?.close(1000, "shutdown")
        ws5m = null
        ws15m = null
    }
}
