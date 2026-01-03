package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.ApiHealthCheckDto
import com.wrbug.polymarketbot.dto.ApiHealthCheckResponse
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.beans.factory.annotation.Value
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingWebSocketService
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * API 健康检查服务
 */
@Service
class ApiHealthCheckService(
    @Value("\${polymarket.clob.base-url}")
    private val clobBaseUrl: String,
    @Value("\${polymarket.data-api.base-url}")
    private val dataApiBaseUrl: String,
    @Value("\${polymarket.gamma.base-url}")
    private val gammaBaseUrl: String,
    @Value("\${polymarket.rtds.ws-url}")
    private val polymarketWsUrl: String,
    @Value("\${polymarket.builder.relayer-url:}")
    private val builderRelayerUrl: String,
    private val rpcNodeService: RpcNodeService
) : ApplicationContextAware {

    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    /**
     * 获取订单推送服务（通过 ApplicationContext 避免循环依赖）
     */
    private fun getOrderPushService(): OrderPushService? {
        return try {
            applicationContext?.getBean(OrderPushService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    /**
     * 获取跟单 WebSocket 服务（通过 ApplicationContext 避免循环依赖）
     */
    private fun getCopyTradingWebSocketService(): CopyTradingWebSocketService? {
        return try {
            applicationContext?.getBean(CopyTradingWebSocketService::class.java)
        } catch (e: BeansException) {
            null
        }
    }
    
    /**
     * 获取 RelayClientService（通过 ApplicationContext 避免循环依赖）
     */
    private fun getRelayClientService(): RelayClientService? {
        return try {
            applicationContext?.getBean(RelayClientService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private val logger = LoggerFactory.getLogger(ApiHealthCheckService::class.java)

    /**
     * 检查所有 API 的健康状态
     */
    suspend fun checkAllApis(): ApiHealthCheckResponse {
        val apis = mutableListOf<ApiHealthCheckDto>()

        // 并行检查所有 API
        coroutineScope {
            val jobs = mutableListOf<Deferred<ApiHealthCheckDto>>(
                async { checkClobApi() },
                async { checkDataApi() },
                async { checkGammaApi() },
                async { checkPolygonRpc() },
                async { checkPolymarketWebSocket() },
                async { checkBuilderRelayerApi() },
                async { checkGitHubApi() }
            )

            jobs.awaitAll().forEach { result ->
                apis.add(result)
            }
        }

        return ApiHealthCheckResponse(apis = apis)
    }

    /**
     * 检查 Polymarket CLOB API
     */
    private suspend fun checkClobApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "$clobBaseUrl/"
        checkApi("Polymarket CLOB API", url)
    }

    /**
     * 检查 Polymarket Data API
     */
    private suspend fun checkDataApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "$dataApiBaseUrl/"
        checkApi("Polymarket Data API", url)
    }

    /**
     * 检查 Polymarket Gamma API
     * 使用 /markets 接口检查 API 可用性（调用实际的业务接口）
     */
    private suspend fun checkGammaApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        return@withContext try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            // 使用 /markets 接口检查（不传参数，返回空列表或少量市场数据）
            val url = "$gammaBaseUrl/markets"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                // 检查响应体是否为有效的 JSON 数组（即使为空数组也可以）
                val responseBody = response.body?.string()
                if (responseBody != null && (responseBody.trim().startsWith("[") || responseBody.trim()
                        .startsWith("{"))
                ) {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
            } else {
                ApiHealthCheckDto(
                    name = "Polymarket Gamma API",
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket Gamma API 失败", e)
            ApiHealthCheckDto(
                name = "Polymarket Gamma API",
                url = "$gammaBaseUrl/markets",
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    /**
     * 检查 Polygon RPC
     * 使用动态获取的可用节点（RpcNodeService 总是返回一个有效的 URL，包括默认节点）
     */
    private suspend fun checkPolygonRpc(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        // 使用 RpcNodeService 获取可用节点（总是返回有效值，包括默认节点）
        val rpcUrl = rpcNodeService.getHttpUrl()
        checkJsonRpcApi("Polygon RPC", rpcUrl)
    }

    /**
     * 检查 Polymarket WebSocket 连接状态
     * 不显示延时，只显示连接状态
     */
    private suspend fun checkPolymarketWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            // 检查订单推送服务的连接状态
            val orderPushService = getOrderPushService()
            val orderPushStatuses = orderPushService?.getConnectionStatuses() ?: emptyMap()
            val orderPushConnected = orderPushStatuses.values.any { it }
            val orderPushTotal = orderPushStatuses.size
            val orderPushConnectedCount = orderPushStatuses.values.count { it }

            // 检查跟单 WebSocket 服务的连接状态
            val copyTradingWebSocketService = getCopyTradingWebSocketService()
            val copyTradingStatuses = copyTradingWebSocketService?.getConnectionStatuses() ?: emptyMap()
            val copyTradingConnected = copyTradingStatuses.values.any { it }
            val copyTradingTotal = copyTradingStatuses.size
            val copyTradingConnectedCount = copyTradingStatuses.values.count { it }

            // 计算总体状态
            val totalConnections = orderPushTotal + copyTradingTotal
            val connectedConnections = orderPushConnectedCount + copyTradingConnectedCount

            val url = polymarketWsUrl
            val hasAnyConnection = orderPushConnected || copyTradingConnected

            if (totalConnections == 0) {
                // 没有配置任何 WebSocket 连接
                ApiHealthCheckDto(
                    name = "Polymarket WebSocket",
                    url = url,
                    status = "skipped",
                    message = "未配置 WebSocket 连接"
                )
            } else if (hasAnyConnection) {
                // 至少有一个连接是活跃的
                val message = if (connectedConnections == totalConnections) {
                    "所有连接正常 ($connectedConnections/$totalConnections)"
                } else {
                    "部分连接正常 ($connectedConnections/$totalConnections)"
                }
                ApiHealthCheckDto(
                    name = "Polymarket WebSocket",
                    url = url,
                    status = "success",
                    message = message
                    // 不设置 responseTime，WebSocket 不显示延时
                )
            } else {
                // 所有连接都断开
                ApiHealthCheckDto(
                    name = "Polymarket WebSocket",
                    url = url,
                    status = "error",
                    message = "所有连接断开 ($connectedConnections/$totalConnections)"
                    // 不设置 responseTime，WebSocket 不显示延时
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "Polymarket WebSocket",
                url = polymarketWsUrl,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    /**
     * 检查普通 HTTP API
     */
    private suspend fun checkApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "success",
                    message = "连接成功",
                    responseTime = responseTime
                )
            } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    /**
     * 检查 JSON-RPC API（如 Polygon RPC）
     */
    private suspend fun checkJsonRpcApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            // 发送一个简单的 JSON-RPC 请求（获取链 ID）
            val jsonRpcRequest = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_chainId",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                ?: "application/json".toMediaType()

            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(mediaType, jsonRpcRequest))
                .header("Content-Type", "application/json")
                .build()

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null && responseBody.contains("\"result\"")) {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
            } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 JSON-RPC API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
    /**
     * 检查 Builder Relayer API
     */
    private suspend fun checkBuilderRelayerApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val relayClientService = getRelayClientService()
        
        if (builderRelayerUrl.isBlank()) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = "未配置",
                status = "skipped",
                message = "未配置 Builder Relayer URL"
            )
        }
        
        if (relayClientService == null) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = builderRelayerUrl,
                status = "error",
                message = "服务未初始化"
            )
        }
        
        if (!relayClientService.isBuilderApiKeyConfigured()) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = builderRelayerUrl,
                status = "skipped",
                message = "Builder API Key 未配置"
            )
        }
        
        return@withContext try {
            val result = relayClientService.checkBuilderRelayerApiHealth()
            result.fold(
                onSuccess = { responseTime ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = builderRelayerUrl,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                },
                onFailure = { e ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = builderRelayerUrl,
                        status = "error",
                        message = e.message ?: "连接失败"
                    )
                }
            )
        } catch (e: Exception) {
            logger.warn("检查 Builder Relayer API 失败", e)
            ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = builderRelayerUrl,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
    /**
     * 检查 GitHub API
     */
    private suspend fun checkGitHubApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/"
        // 直接使用 GitHub API 根端点检查可用性
        checkApi("GitHub API", url)
    }
}

