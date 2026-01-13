package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.getProxyConfig
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一的链上 WebSocket 服务
 * 管理唯一的 WebSocket 连接，其他服务通过订阅的方式接收链上事件
 */
@Service
class UnifiedOnChainWsService(
    private val rpcNodeService: RpcNodeService,
    private val retrofitFactory: RetrofitFactory,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(UnifiedOnChainWsService::class.java)
    
    @Value("\${copy.trading.onchain.ws.reconnect.delay:3000}")
    private var reconnectDelay: Long = 3000  // 重连延迟（毫秒），默认3秒
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 存储所有地址的连接：address -> AddressWsConnection
    private val addressConnections = ConcurrentHashMap<String, AddressWsConnection>()
    
    /**
     * 订阅信息
     */
    data class SubscriptionInfo(
        val subscriptionId: String,  // 订阅的唯一标识
        val address: String,  // 要监听的地址（Leader 地址或账户代理地址）
        val entityType: String,  // 实体类型：LEADER 或 ACCOUNT
        val entityId: Long,  // 实体 ID（Leader ID 或 Account ID）
        val callback: suspend (String, OkHttpClient, EthereumRpcApi) -> Unit  // 回调函数
    )
    
    /**
     * 订阅地址监听
     * @param subscriptionId 订阅的唯一标识（建议格式："{entityType}_{entityId}"）
     * @param address 要监听的地址（Leader 地址或账户代理地址）
     * @param entityType 实体类型：LEADER 或 ACCOUNT
     * @param entityId 实体 ID（Leader ID 或 Account ID）
     * @param callback 回调函数，当检测到该地址的交易时调用
     * @return 是否订阅成功
     */
    fun subscribe(
        subscriptionId: String,
        address: String,
        entityType: String,
        entityId: Long,
        callback: suspend (String, OkHttpClient, EthereumRpcApi) -> Unit
    ): Boolean {
        try {
            val lowerAddress = address.lowercase()
            
            // 找到或创建该地址的连接
            val connection = addressConnections.computeIfAbsent(lowerAddress) {
                AddressWsConnection(it).apply { start() }
            }
            
            // 创建订阅信息
            val subscription = SubscriptionInfo(
                subscriptionId = subscriptionId,
                address = lowerAddress,
                entityType = entityType,
                entityId = entityId,
                callback = callback
            )
            
            // 添加订阅
            connection.addSubscription(subscription)
            
            logger.info("订阅地址监听: subscriptionId=$subscriptionId, address=$address, entityType=$entityType, entityId=$entityId")
            return true
        } catch (e: Exception) {
            logger.error("订阅地址监听失败: subscriptionId=$subscriptionId, address=$address, error=${e.message}", e)
            return false
        }
    }
    
    /**
     * 取消订阅
     */
    fun unsubscribe(subscriptionId: String) {
        // 遍历所有连接找到含有该订阅的连接
        for (connection in addressConnections.values) {
            if (connection.hasSubscription(subscriptionId)) {
                connection.removeSubscription(subscriptionId)
                
                // 如果该连接没有订阅了，停止并移除
                if (connection.isSubscriptionsEmpty()) {
                    connection.stop()
                    addressConnections.remove(connection.address)
                    logger.info("连接已无订阅，关闭连接: address=${connection.address}")
                }
                
                logger.info("取消订阅: subscriptionId=$subscriptionId")
                return
            }
        }
    }
    
    /**
     * 停止所有服务
     */
    fun stop() {
        for (connection in addressConnections.values) {
            connection.stop()
        }
        addressConnections.clear()
    }

    /**
     * 获取连接状态
     * @return Map<address, isConnected>
     */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return addressConnections.mapValues { (_, connection) -> connection.isConnected() }
    }

    @PostConstruct
    fun init() {
        logger.info("统一链上 WebSocket 服务已初始化 (独立连接模式)")
    }

    @PreDestroy
    fun destroy() {
        stop()
        scope.cancel()
    }

    /**
     * 单个地址的 WebSocket 连接管理
     */
    inner class AddressWsConnection(val address: String) {
        private var webSocket: WebSocket? = null
        @Volatile
        private var isConnected = false
        
        // 订阅ID计数器（用于请求 ID）
        private var requestIdCounter = AtomicInteger(0)
        
        // 连接任务
        private var connectionJob: Job? = null
        
        // 该连接下的所有订阅：subscriptionId -> SubscriptionInfo
        // 理论上一个地址可能被多个业务订阅（如：既是被跟单者又是普通监控），虽然业务上通常只有一个
        private val subscriptions = ConcurrentHashMap<String, SubscriptionInfo>()
        
        // 存储请求 ID 到订阅 ID 的映射：requestId -> subscriptionId
        private val requestIdToSubscriptionId = ConcurrentHashMap<Int, String>()
        
        // 存储 RPC subscriptionId 到订阅 ID 的映射：rpcSubscriptionId -> subscriptionId
        private val rpcSubscriptionIdToSubscriptionId = ConcurrentHashMap<String, String>()

        fun start() {
            if (connectionJob != null && connectionJob!!.isActive) return
            connectionJob = scope.launch {
                startConnectionLoop()
            }
        }

        fun stop() {
            connectionJob?.cancel()
            connectionJob = null
            webSocket?.close(1000, "停止监听")
            webSocket = null
            isConnected = false
            subscriptions.clear()
            requestIdToSubscriptionId.clear()
            rpcSubscriptionIdToSubscriptionId.clear()
        }

        fun addSubscription(subscription: SubscriptionInfo) {
            // 如果已经存在，先移除旧的
            removeSubscription(subscription.subscriptionId)
            subscriptions[subscription.subscriptionId] = subscription
            
            // 如果已经连接，立即发送链上订阅请求
            if (isConnected) {
                scope.launch {
                    subscribeAddressOnChain(subscription)
                }
            }
        }

        fun removeSubscription(subscriptionId: String) {
            subscriptions.remove(subscriptionId)
            // 不需要显式发送 eth_unsubscribe，因为连接是 per-address 的，
            // 只要只要连接还在，就保持该地址相关的所有 logs 订阅。
            // 只有当所有 subscription 都移除了，连接才会关闭。
        }

        fun hasSubscription(subscriptionId: String): Boolean {
            return subscriptions.containsKey(subscriptionId)
        }

        fun isSubscriptionsEmpty(): Boolean {
            return subscriptions.isEmpty()
        }

        fun isConnected(): Boolean {
            return isConnected
        }

        private suspend fun startConnectionLoop() {
            while (scope.isActive) {
                try {
                    if (subscriptions.isEmpty()) {
                        // 如果启动循环时还没订阅（不太可能，通常是先 addSubscription 再 start，或者是 start 后 addSubscription）
                        // 或者订阅被清空了，外部应当掉 stop，但这里作为防守
                        delay(1000) 
                        continue
                    }

                    if (isConnected && webSocket != null) {
                        waitForDisconnect()
                        continue
                    }

                    // 获取可用的 RPC 节点
                    val wsUrl = rpcNodeService.getWsUrl()
                    val httpUrl = rpcNodeService.getHttpUrl()

                    logger.info("[$address] 连接链上 WebSocket: $wsUrl")

                    val httpClient = createHttpClient()
                    val rpcApi = retrofitFactory.createEthereumRpcApi(httpUrl)

                    connectWebSocket(wsUrl, httpClient, rpcApi)
                    waitForConnect()

                    if (isConnected) {
                        logger.info("[$address] WebSocket 连接已建立，开始注册订阅")
                        // 重新为所有订阅注册链上监听
                        for (subscription in subscriptions.values) {
                            subscribeAddressOnChain(subscription)
                        }
                        waitForDisconnect()
                    }

                    logger.info("[$address] WebSocket 连接断开，等待 ${reconnectDelay}ms 后重连")
                    delay(reconnectDelay)

                } catch (e: Exception) {
                    logger.error("[$address] 连接异常: ${e.message}", e)
                    delay(reconnectDelay)
                }
            }
        }

        private fun connectWebSocket(wsUrl: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
            webSocket?.close(1000, "重新连接")
            webSocket = null
            isConnected = false

            val request = Request.Builder().url(wsUrl).build()
            
            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    isConnected = true
                    logger.info("[$address] 链上 WebSocket 连接成功")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { handleMessage(text, httpClient, rpcApi) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    scope.launch { handleMessage(bytes.utf8(), httpClient, rpcApi) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    logger.warn("[$address] 链上 WebSocket 连接关闭: code=$code, reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    logger.warn("[$address] 链上 WebSocket 连接已关闭: code=$code, reason=$reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    logger.error("[$address] 链上 WebSocket 连接失败: ${t.message}", t)
                    isConnected = false
                }
            })
        }

        private suspend fun subscribeAddressOnChain(subscription: SubscriptionInfo) {
            if (webSocket == null || !isConnected) return
            
            val walletTopic = OnChainWsUtils.addressToTopic32(address)
            val subId = subscription.subscriptionId
            
            try {
                // 订阅该地址相关的所有事件
                // USDC Transfer (from/to)
                subscribeLogs(OnChainWsUtils.USDC_CONTRACT, listOf(OnChainWsUtils.ERC20_TRANSFER_TOPIC, walletTopic), subId)
                subscribeLogs(OnChainWsUtils.USDC_CONTRACT, listOf(OnChainWsUtils.ERC20_TRANSFER_TOPIC, null, walletTopic), subId)
                
                // ERC1155 TransferSingle (from/to)
                subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_SINGLE_TOPIC, null, walletTopic), subId)
                subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_SINGLE_TOPIC, null, null, walletTopic), subId)
                
                // ERC1155 TransferBatch (from/to)
                subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_BATCH_TOPIC, null, walletTopic), subId)
                subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_BATCH_TOPIC, null, null, walletTopic), subId)
                
                logger.debug("[$address] 已发送链上订阅请求: subscriptionId=$subId")
            } catch (e: Exception) {
                logger.error("[$address] 发送链上订阅请求失败: error=${e.message}", e)
            }
        }

        private fun subscribeLogs(contractAddress: String, topics: List<String?>, subscriptionId: String) {
            val ws = webSocket ?: return
            
            val topicsArray = gson.toJsonTree(topics).asJsonArray
            val logParams = JsonObject()
            logParams.addProperty("address", contractAddress.lowercase())
            logParams.add("topics", topicsArray)
            
            val requestId = requestIdCounter.incrementAndGet()
            requestIdToSubscriptionId[requestId] = subscriptionId
            
            val request = JsonObject()
            request.addProperty("jsonrpc", "2.0")
            request.addProperty("id", requestId)
            request.addProperty("method", "eth_subscribe")
            val paramsArray = JsonArray()
            paramsArray.add("logs")
            paramsArray.add(logParams)
            request.add("params", paramsArray)
            
            ws.send(gson.toJson(request))
        }

        private suspend fun handleMessage(text: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
            try {
                val message = gson.fromJson(text, JsonObject::class.java)

                // 1. 处理订阅响应 (eth_subscribe response)
                if (message.has("result") && message.has("id")) {
                    val requestId = message.get("id")?.asInt
                    val rpcSubscriptionId = message.get("result")?.asString
                    
                    if (requestId != null && rpcSubscriptionId != null) {
                        val subId = requestIdToSubscriptionId.remove(requestId)
                        if (subId != null) {
                            rpcSubscriptionIdToSubscriptionId[rpcSubscriptionId] = subId
                            logger.debug("[$address] 链上订阅成功: mapped connection rpcSubId=$rpcSubscriptionId to localSubId=$subId")
                        }
                    }
                    return
                }

                // 2. 处理日志通知 (eth_subscription)
                val method = message.get("method")?.asString
                if (method == "eth_subscription") {
                    val params = message.getAsJsonObject("params") ?: return
                    val rpcSubParam = params.get("subscription")?.asString
                    val result = params.getAsJsonObject("result") ?: return
                    val txHash = result.get("transactionHash")?.asString

                    if (txHash != null && rpcSubParam != null) {
                        // 找到触发此通知的本地订阅 ID
                        // 因为我们在这个连接里只订阅了 this.address，所以理论上所有通知都跟这个 address 有关
                        // 但我们需要找到对应的 callback
                        val localSubId = rpcSubscriptionIdToSubscriptionId[rpcSubParam]
                        
                        if (localSubId != null) {
                            val subscription = subscriptions[localSubId]
                            if (subscription != null) {
                                logger.info("[$address] 收到交易通知: txHash=$txHash, subId=$localSubId")
                                runCatching {
                                    subscription.callback(txHash, httpClient, rpcApi)
                                }.onFailure { e ->
                                    logger.error("[$address] 回调执行失败: ${e.message}", e)
                                }
                            }
                        } else {
                            // 找不到具体是哪个订阅请求触发的（可能是重启后之前的订阅残留？或者映射丢失？）
                            // 在单地址单连接模式下，只要是这个 connection 收到的，肯定是关于这个 address 的
                            // 我们可以尝试通知所有订阅者（通常一个地址只有一个订阅者，除非此地址既是Leader又是User）
                            logger.warn("[$address] 未找到映射的订阅ID: rpcSubId=$rpcSubParam. 广播给所有订阅者.")
                            subscriptions.values.forEach { sub ->
                                runCatching {
                                    sub.callback(txHash, httpClient, rpcApi)
                                }.onFailure { e ->
                                    logger.error("[$address] 广播回调执行失败: ${e.message}", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("[$address] 处理消息失败: ${e.message}", e)
            }
        }
        
        private suspend fun waitForConnect() {
            var waited = 0L
            val timeout = 15000L
            while (!isConnected && waited < timeout) {
                delay(100)
                waited += 100
            }
            if (!isConnected) logger.warn("[$address] WebSocket 连接超时")
        }

        private suspend fun waitForDisconnect() {
            while (isConnected && scope.isActive) {
                delay(1000)
            }
        }
        
        private fun createHttpClient(): OkHttpClient {
            val proxy = getProxyConfig()
            val builder = createClient()
            if (proxy != null) builder.proxy(proxy)
            return builder.build()
        }
    }
}

