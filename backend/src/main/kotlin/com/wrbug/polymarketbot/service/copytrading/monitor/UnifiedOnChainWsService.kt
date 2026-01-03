package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
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
    
    // WebSocket 连接（唯一）
    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    
    // 订阅ID计数器（用于请求 ID）
    private var requestIdCounter = 0
    
    // 连接任务（确保只有一个连接任务在运行）
    private var connectionJob: Job? = null
    
    // 存储所有订阅：subscriptionId -> 订阅信息
    private val subscriptions = ConcurrentHashMap<String, SubscriptionInfo>()
    
    // 存储请求 ID 到订阅 ID 的映射：requestId -> subscriptionId
    // 用于在收到订阅响应时，将 subscription ID 关联到对应的订阅
    private val requestIdToSubscriptionId = ConcurrentHashMap<Int, String>()
    
    // 存储 RPC subscriptionId 到订阅 ID 的映射：rpcSubscriptionId -> subscriptionId
    // 用于在收到日志通知时，知道是哪个订阅
    private val rpcSubscriptionIdToSubscriptionId = ConcurrentHashMap<String, String>()
    
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
            // 如果已经订阅，先取消
            if (subscriptions.containsKey(subscriptionId)) {
                unsubscribe(subscriptionId)
            }
            
            // 创建订阅信息
            val subscription = SubscriptionInfo(
                subscriptionId = subscriptionId,
                address = address.lowercase(),
                entityType = entityType,
                entityId = entityId,
                callback = callback
            )
            
            subscriptions[subscriptionId] = subscription
            
            // 如果已连接，立即订阅
            if (isConnected) {
                scope.launch {
                    subscribeAddress(subscription)
                }
            } else {
                // 如果未连接，启动连接
                startConnection()
            }
            
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
        val subscription = subscriptions.remove(subscriptionId)
        
        if (subscription != null && isConnected) {
            // 取消该订阅的所有 RPC 订阅
            scope.launch {
                // 查找该订阅的所有 RPC subscriptionId
                val rpcSubscriptionIds = rpcSubscriptionIdToSubscriptionId.entries
                    .filter { it.value == subscriptionId }
                    .map { it.key }
                
                for (rpcSubId in rpcSubscriptionIds) {
                    unsubscribeRpc(rpcSubId)
                    rpcSubscriptionIdToSubscriptionId.remove(rpcSubId)
                }
            }
            
            logger.info("取消订阅: subscriptionId=$subscriptionId")
        }
        
        // 如果没有订阅了，停止连接
        if (subscriptions.isEmpty()) {
            stop()
        }
    }
    
    /**
     * 启动连接（如果还没有连接）
     */
    private fun startConnection() {
        // 如果没有订阅，不启动连接
        if (subscriptions.isEmpty()) {
            return
        }
        
        // 如果连接任务已经在运行，不重复启动
        if (connectionJob != null && connectionJob!!.isActive) {
            return
        }
        
        // 启动连接任务
        connectionJob = scope.launch {
            startConnectionLoop()
        }
    }
    
    /**
     * 启动连接循环
     */
    private suspend fun startConnectionLoop() {
        while (scope.isActive) {
            try {
                // 如果没有订阅，停止连接
                if (subscriptions.isEmpty()) {
                    logger.info("没有订阅，停止连接")
                    stop()
                    break
                }
                
                // 如果已经连接，等待断开
                if (isConnected && webSocket != null) {
                    waitForDisconnect()
                    continue
                }
                
                // 获取可用的 RPC 节点
                val wsUrl = rpcNodeService.getWsUrl()
                val httpUrl = rpcNodeService.getHttpUrl()
                
                if (wsUrl.isBlank() || httpUrl.isBlank()) {
                    logger.warn("没有可用的 RPC 节点，等待重试...")
                    delay(reconnectDelay)
                    continue
                }
                
                logger.info("连接链上 WebSocket: $wsUrl (${subscriptions.size} 个订阅)")
                
                // 创建 HTTP 客户端（用于 RPC 调用）
                val httpClient = createHttpClient()
                
                // 创建 RPC API 客户端
                val rpcApi = retrofitFactory.createEthereumRpcApi(httpUrl)
                
                // 连接 WebSocket
                connectWebSocket(wsUrl, httpClient, rpcApi)
                
                // 等待连接建立
                waitForConnect()
                
                // 如果连接成功，订阅所有地址
                if (isConnected) {
                    logger.info("WebSocket 连接已建立，开始订阅")
                    for (subscription in subscriptions.values) {
                        subscribeAddress(subscription)
                    }
                    
                    // 等待连接断开
                    waitForDisconnect()
                }
                
                // 连接断开后，如果没有订阅了，不再重连
                if (subscriptions.isEmpty()) {
                    logger.info("没有订阅，停止重连")
                    break
                }
                
                // 等待后重连
                logger.info("WebSocket 连接断开，等待 ${reconnectDelay}ms 后重连")
                delay(reconnectDelay)
                
            } catch (e: Exception) {
                logger.error("连接异常: ${e.message}", e)
                delay(reconnectDelay)
            }
        }
    }
    
    /**
     * 创建 HTTP 客户端
     */
    private fun createHttpClient(): OkHttpClient {
        val proxy = getProxyConfig()
        val builder = createClient()
        
        if (proxy != null) {
            builder.proxy(proxy)
        }
        
        return builder.build()
    }
    
    /**
     * 连接 WebSocket
     */
    private fun connectWebSocket(wsUrl: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
        // 先关闭旧连接
        webSocket?.close(1000, "重新连接")
        webSocket = null
        isConnected = false
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnected = true
                logger.info("链上 WebSocket 连接成功")
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleMessage(text, httpClient, rpcApi)
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    handleMessage(bytes.utf8(), httpClient, rpcApi)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                logger.warn("链上 WebSocket 连接关闭: code=$code, reason=$reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                logger.warn("链上 WebSocket 连接已关闭: code=$code, reason=$reason")
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                logger.error("链上 WebSocket 连接失败: ${t.message}", t)
                isConnected = false
            }
        })
    }
    
    /**
     * 等待连接建立
     */
    private suspend fun waitForConnect() {
        var waited = 0L
        val timeout = 15000L  // 15秒超时
        
        while (!isConnected && waited < timeout) {
            delay(100)
            waited += 100
        }
        
        if (!isConnected) {
            logger.warn("WebSocket 连接超时，等待重连")
        }
    }
    
    /**
     * 等待连接断开
     */
    private suspend fun waitForDisconnect() {
        while (isConnected && scope.isActive) {
            delay(1000)
        }
    }
    
    /**
     * 订阅地址（为每个地址订阅 6 个事件）
     */
    private suspend fun subscribeAddress(subscription: SubscriptionInfo) {
        if (webSocket == null || !isConnected) {
            return
        }
        
        val address = subscription.address
        val walletTopic = OnChainWsUtils.addressToTopic32(address)
        val subscriptionId = subscription.subscriptionId
        
        try {
            // 订阅 USDC Transfer (from wallet)
            subscribeLogs(OnChainWsUtils.USDC_CONTRACT, listOf(OnChainWsUtils.ERC20_TRANSFER_TOPIC, walletTopic), subscriptionId)
            
            // 订阅 USDC Transfer (to wallet)
            subscribeLogs(OnChainWsUtils.USDC_CONTRACT, listOf(OnChainWsUtils.ERC20_TRANSFER_TOPIC, null, walletTopic), subscriptionId)
            
            // 订阅 ERC1155 TransferSingle (from wallet)
            subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_SINGLE_TOPIC, null, walletTopic), subscriptionId)
            
            // 订阅 ERC1155 TransferSingle (to wallet)
            subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_SINGLE_TOPIC, null, null, walletTopic), subscriptionId)
            
            // 订阅 ERC1155 TransferBatch (from wallet)
            subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_BATCH_TOPIC, null, walletTopic), subscriptionId)
            
            // 订阅 ERC1155 TransferBatch (to wallet)
            subscribeLogs(OnChainWsUtils.ERC1155_CONTRACT, listOf(OnChainWsUtils.ERC1155_TRANSFER_BATCH_TOPIC, null, null, walletTopic), subscriptionId)
            
            logger.debug("已订阅地址: subscriptionId=$subscriptionId, address=$address")
        } catch (e: Exception) {
            logger.error("订阅地址失败: subscriptionId=$subscriptionId, address=$address, error=${e.message}", e)
        }
    }
    
    /**
     * 订阅日志
     */
    private fun subscribeLogs(address: String, topics: List<String?>, subscriptionId: String) {
        val ws = webSocket ?: return
        
        val params = mapOf(
            "address" to address.lowercase(),
            "topics" to topics.filterNotNull()
        )
        
        val requestId = ++requestIdCounter
        requestIdToSubscriptionId[requestId] = subscriptionId
        
        val request = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId,
            "method" to "eth_subscribe",
            "params" to listOf("logs", params)
        )
        
        val message = gson.toJson(request)
        ws.send(message)
    }
    
    /**
     * 取消 RPC 订阅
     */
    private fun unsubscribeRpc(rpcSubscriptionId: String) {
        val ws = webSocket ?: return
        
        val requestId = ++requestIdCounter
        val request = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId,
            "method" to "eth_unsubscribe",
            "params" to listOf(rpcSubscriptionId)
        )
        
        val message = gson.toJson(request)
        ws.send(message)
    }
    
    /**
     * 处理 WebSocket 消息
     */
    private suspend fun handleMessage(text: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
        try {
            val message = gson.fromJson(text, JsonObject::class.java)
            
            // 处理订阅响应
            if (message.has("result") && message.has("id")) {
                val requestId = message.get("id")?.asInt
                val rpcSubscriptionId = message.get("result")?.asString
                
                if (requestId != null && rpcSubscriptionId != null) {
                    val subscriptionId = requestIdToSubscriptionId.remove(requestId)
                    if (subscriptionId != null) {
                        // 保存 RPC subscriptionId 到订阅的映射
                        rpcSubscriptionIdToSubscriptionId[rpcSubscriptionId] = subscriptionId
                        logger.debug("订阅成功: subscriptionId=$subscriptionId, rpcSubscriptionId=$rpcSubscriptionId")
                    }
                }
                return
            }
            
            // 处理日志通知
            if (message.has("params")) {
                val params = message.getAsJsonObject("params")
                val subscriptionIdParam = params.get("subscription")?.asString
                val result = params.getAsJsonObject("result")
                
                if (result != null) {
                    val txHash = result.get("transactionHash")?.asString
                    if (txHash != null && subscriptionIdParam != null) {
                        // 根据 RPC subscriptionId 找到对应的订阅
                        val subscriptionId = rpcSubscriptionIdToSubscriptionId[subscriptionIdParam]
                        if (subscriptionId != null) {
                            // 处理交易，分发给对应的订阅者
                            processTransactionForSubscription(txHash, subscriptionId, httpClient, rpcApi)
                        } else {
                            // 如果没有找到订阅，可能是新订阅还未建立映射，尝试处理所有订阅
                            processTransaction(txHash, httpClient, rpcApi)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理 WebSocket 消息失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理交易（为特定订阅）
     * 直接调用订阅的回调
     */
    private suspend fun processTransactionForSubscription(
        txHash: String,
        subscriptionId: String,
        httpClient: OkHttpClient,
        rpcApi: EthereumRpcApi
    ) {
        val subscription = subscriptions[subscriptionId] ?: return
        
        try {
            subscription.callback(txHash, httpClient, rpcApi)
        } catch (e: Exception) {
            logger.error("调用订阅回调失败: subscriptionId=$subscriptionId, txHash=$txHash, error=${e.message}", e)
        }
    }
    
    /**
     * 处理交易（为所有订阅，用于兼容）
     * 解析交易中的 Transfer 事件，分发给所有订阅者
     */
    private suspend fun processTransaction(txHash: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
        try {
            // 获取交易 receipt
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )
            
            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                return
            }
            
            val receiptRpcResponse = receiptResponse.body()!!
            if (receiptRpcResponse.error != null || receiptRpcResponse.result == null) {
                return
            }
            
            // 使用 Gson 解析 receipt JSON
            val receiptJson = receiptRpcResponse.result.asJsonObject
            
            // 解析 receipt 中的 Transfer 日志
            val logs = receiptJson.getAsJsonArray("logs") ?: return
            val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
            
            // 为每个订阅检查是否匹配，如果匹配则调用回调
            for (subscription in subscriptions.values) {
                val address = subscription.address
                
                // 检查该地址是否参与了交易（通过检查 Transfer 日志）
                val isInvolved = erc20Transfers.any { 
                    it.from.lowercase() == address || it.to.lowercase() == address 
                } || erc1155Transfers.any { 
                    it.from.lowercase() == address || it.to.lowercase() == address 
                }
                
                if (isInvolved) {
                    // 该地址参与了交易，调用回调
                    try {
                        subscription.callback(txHash, httpClient, rpcApi)
                    } catch (e: Exception) {
                        logger.error("调用订阅回调失败: subscriptionId=${subscription.subscriptionId}, txHash=$txHash, error=${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理交易失败: txHash=$txHash, ${e.message}", e)
        }
    }
    
    /**
     * 停止连接
     */
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        
        // 关闭 WebSocket 连接
        webSocket?.close(1000, "停止监听")
        webSocket = null
        isConnected = false
        
        // 清空订阅信息
        subscriptions.clear()
        requestIdToSubscriptionId.clear()
        rpcSubscriptionIdToSubscriptionId.clear()
    }
    
    @PostConstruct
    fun init() {
        // 服务启动时不自动连接，等待有订阅时再连接
        logger.info("统一链上 WebSocket 服务已初始化")
    }
    
    @PreDestroy
    fun destroy() {
        stop()
        scope.cancel()
    }
}

