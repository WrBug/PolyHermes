package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.entity.NodeHealthStatus
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.entity.RpcProviderType
import com.wrbug.polymarketbot.repository.RpcNodeConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * RPC 节点管理服务
 * 负责管理用户配置的 Polygon RPC 节点
 */
@Service
class RpcNodeService(
    private val rpcNodeConfigRepository: RpcNodeConfigRepository,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(RpcNodeService::class.java)
    
    companion object {
        // 默认公共节点
        private const val DEFAULT_RPC_URL = "https://polygon.publicnode.com"
        private const val DEFAULT_WS_URL = "wss://polygon.publicnode.com"
        
        // 主流服务商 URL 模板
        private val PROVIDER_HTTP_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "https://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "https://polygon-mainnet.infura.io/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "https://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "https://polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "https://go.getblock.io/{apiKey}/"
        )
        
        private val PROVIDER_WS_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "wss://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "wss://polygon-mainnet.infura.io/ws/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "wss://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "wss://ws-polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "wss://go.getblock.io/{apiKey}/"
        )
    }
    
    /**
     * 获取所有节点配置（不包含默认节点）
     * 默认节点作为兜底，不应该返回给前端
     */
    fun getAllNodes(): List<RpcNodeConfig> {
        val allNodes = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
        
        // 过滤掉默认节点，只返回用户配置的节点
        return allNodes.filterNot { isDefaultNode(it) }
    }
    
    /**
     * 获取所有节点配置（包含默认节点，用于内部使用）
     * 只返回启用的节点，禁用的节点会被忽略
     * 默认节点始终排在最后
     */
    fun getAllNodesWithDefault(): List<RpcNodeConfig> {
        // 只查询启用的节点
        val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
        
        // 分离默认节点和用户配置的节点
        val (defaultNodes, userNodes) = allNodes.partition { isDefaultNode(it) }
        
        // 返回用户配置的节点，默认节点排在最后（如果存在）
        return userNodes + defaultNodes
    }
    
    /**
     * 判断是否是默认节点
     */
    private fun isDefaultNode(node: RpcNodeConfig): Boolean {
        return node.httpUrl == DEFAULT_RPC_URL || 
               node.httpUrl == DEFAULT_RPC_URL.removeSuffix("/") ||
               (node.providerType == RpcProviderType.PUBLIC.name && 
                (node.httpUrl.contains("polygon.publicnode.com") || 
                 node.httpUrl.contains("publicnode.com")))
    }
    
    /**
     * 获取第一个可用的节点
     * 按优先级顺序遍历所有启用的节点，找到第一个真正可用的节点
     * 如果所有节点都不可用，返回默认节点
     * @return  可用节点的配置,如果没有可用节点则返回失败
     */
    fun getAvailableNode(): Result<RpcNodeConfig> {
        return try {
            val nodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
                .filterNot { isDefaultNode(it) }  // 排除默认节点
            
            if (nodes.isEmpty()) {
                logger.warn("没有配置任何 RPC 节点,使用默认节点: $DEFAULT_RPC_URL")
                return Result.failure(IllegalStateException("没有配置任何 RPC 节点"))
            }
            
            // 优先使用最近检查状态为 HEALTHY 的节点
            val healthyNodes = nodes.filter { 
                it.lastCheckStatus == NodeHealthStatus.HEALTHY.name 
            }
            
            // 先尝试使用健康的节点（按优先级排序）
            for (node in healthyNodes) {
                try {
                    // 快速验证节点是否仍然可用（使用较短的超时时间）
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.debug("使用健康的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                    // 继续尝试下一个节点
                }
            }
            
            // 如果没有健康的节点，尝试验证所有节点（按优先级）
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.info("找到可用的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                    // 继续尝试下一个节点
                }
            }
            
            // 所有节点都不可用，返回失败
            logger.warn("所有 RPC 节点都不可用，将使用默认节点: $DEFAULT_RPC_URL")
            Result.failure(IllegalStateException("所有 RPC 节点都不可用"))
        } catch (e: Exception) {
            logger.error("获取可用节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取节点的 HTTP URL (如果没有配置,使用默认节点)
     */
    fun getHttpUrl(): String {
        val nodeResult = getAvailableNode()
        return if (nodeResult.isSuccess) {
            nodeResult.getOrNull()?.httpUrl ?: DEFAULT_RPC_URL
        } else {
            logger.warn("没有可用的用户配置节点,使用默认节点")
            DEFAULT_RPC_URL
        }
    }
    
    /**
     * 获取节点的 WebSocket URL (如果没有配置,使用默认节点)
     */
    fun getWsUrl(): String {
        val nodeResult = getAvailableNode()
        return if (nodeResult.isSuccess) {
            nodeResult.getOrNull()?.wsUrl ?: DEFAULT_WS_URL
        } else {
            logger.warn("没有可用的用户配置节点,使用默认 WS 节点")
            DEFAULT_WS_URL
        }
    }
    
    /**
     * 添加节点
     */
    @Transactional
    fun addNode(request: AddRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            // 1. 验证请求
            val providerType = try {
                RpcProviderType.valueOf(request.providerType.uppercase())
            } catch (e: IllegalArgumentException) {
                return Result.failure(IllegalArgumentException("不支持的服务商类型: ${request.providerType}"))
            }
            
            // 2. 构建 HTTP 和 WS URL
            val (httpUrl, wsUrl) = if (providerType == RpcProviderType.CUSTOM) {
                // 自定义节点,使用用户提供的 URL
                if (request.httpUrl.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("自定义节点必须提供 HTTP URL"))
                }
                Pair(request.httpUrl, request.wsUrl)
            } else {
                // 主流服务商,使用模板生成 URL
                if (request.apiKey.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("${request.providerType} 节点必须提供 API Key"))
                }
                val httpTemplate = PROVIDER_HTTP_TEMPLATES[providerType]
                    ?: return Result.failure(IllegalArgumentException("未找到 ${request.providerType} 的 HTTP URL 模板"))
                val wsTemplate = PROVIDER_WS_TEMPLATES[providerType]
                Pair(
                    httpTemplate.replace("{apiKey}", request.apiKey),
                    wsTemplate?.replace("{apiKey}", request.apiKey)
                )
            }
            
            // 3. 校验节点可用性
            val validationResult = validateNode(httpUrl, wsUrl)
            if (validationResult.isFailure) {
                return Result.failure(validationResult.exceptionOrNull() ?: Exception("节点验证失败"))
            }
            
            val checkResult = validationResult.getOrNull()!!
            
            // 检查节点是否健康，如果不健康则不允许添加
            if (checkResult.status != NodeHealthStatus.HEALTHY) {
                return Result.failure(IllegalArgumentException("节点不可用: ${checkResult.message}"))
            }
            
            // 4. 加密 API Key (如果有)
            val encryptedApiKey = request.apiKey?.let { cryptoUtils.encrypt(it) }
            
            // 5. 获取当前最大优先级
            val maxPriority = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
                .maxOfOrNull { it.priority } ?: 0
            
            // 6. 创建节点配置
            val node = RpcNodeConfig(
                providerType = providerType.name,
                name = request.name,
                httpUrl = httpUrl,
                wsUrl = wsUrl,
                apiKey = encryptedApiKey,
                enabled = true,
                priority = maxPriority + 1,  // 新节点放到最后
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs
            )
            
            val savedNode = rpcNodeConfigRepository.save(node)
            logger.info("成功添加 RPC 节点: ${savedNode.name} (${savedNode.httpUrl})")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("添加节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新节点
     * 默认节点不允许更新（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun updateNode(request: UpdateRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            val node = rpcNodeConfigRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: ${request.id}"))
            
            // 如果是默认节点，不允许更新
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新"))
            }
            
            // 更新字段
            val updatedNode = node.copy(
                name = request.name ?: node.name,
                enabled = request.enabled ?: node.enabled,
                priority = request.priority ?: node.priority,
                updatedAt = System.currentTimeMillis()
            )
            
            val savedNode = rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新 RPC 节点: ${savedNode.name}")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("更新节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除节点
     * 默认节点不允许删除（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun deleteNode(id: Long): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            
            // 如果是默认节点，不允许删除
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许删除"))
            }
            
            rpcNodeConfigRepository.delete(node)
            logger.info("成功删除 RPC 节点: ${node.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新节点优先级
     * 默认节点不允许更新优先级（作为兜底，始终排在最后）
     */
    @Transactional
    fun updatePriority(id: Long, priority: Int): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            
            // 如果是默认节点，不允许更新优先级
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新优先级"))
            }
            
            val updatedNode = node.copy(
                priority = priority,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新节点优先级: ${node.name} -> $priority")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("更新节点优先级失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查单个节点健康状态
     * 默认节点不应该被检查（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun checkNodeHealth(nodeId: Long): Result<NodeCheckResult> {
        return try {
            val node = rpcNodeConfigRepository.findById(nodeId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $nodeId"))
            
            // 如果是默认节点，不允许检查
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许检查"))
            }
            
            val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrThrow()
            
            // 更新节点健康状态
            val updatedNode = node.copy(
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("检查节点健康状态: ${node.name} -> ${checkResult.status}")
            Result.success(checkResult)
        } catch (e: Exception) {
            logger.error("检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 批量检查所有节点健康状态（不包含默认节点和禁用的节点）
     * 默认节点作为兜底，不应该返回给前端
     * 禁用的节点不应该被检查
     */
    @Transactional
    fun checkAllNodesHealth(): Result<Map<Long, NodeCheckResult>> {
        return try {
            // 只查询启用的节点，过滤掉默认节点和禁用的节点
            val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
            // 过滤掉默认节点，只检查用户配置的启用节点
            val nodes = allNodes.filterNot { isDefaultNode(it) }
            val results = mutableMapOf<Long, NodeCheckResult>()
            
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null) {
                        results[node.id!!] = checkResult
                        
                        // 更新节点状态
                        val updatedNode = node.copy(
                            lastCheckTime = checkResult.checkTime,
                            lastCheckStatus = checkResult.status.name,
                            responseTimeMs = checkResult.responseTimeMs,
                            updatedAt = System.currentTimeMillis()
                        )
                        rpcNodeConfigRepository.save(updatedNode)
                    }
                } catch (e: Exception) {
                    logger.error("检查节点 ${node.name} 失败: ${e.message}", e)
                }
            }
            
            Result.success(results)
        } catch (e: Exception) {
            logger.error("批量检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 校验节点可用性
     * 调用 eth_blockNumber 验证节点是否可用
     */
    private fun validateNode(httpUrl: String, wsUrl: String?): Result<NodeCheckResult> {
        return try {
            logger.debug("开始验证节点: $httpUrl")
            
            // 创建临时 RPC API
            val rpcApi = retrofitFactory.createEthereumRpcApi(httpUrl)
            
            // 调用 eth_blockNumber
            val startTime = System.currentTimeMillis()
            val rpcRequest = JsonRpcRequest(
                method = "eth_blockNumber",
                params = emptyList()
            )
            
            val response = kotlinx.coroutines.runBlocking { rpcApi.call(rpcRequest) }
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("节点验证失败: HTTP ${response.code()}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "HTTP 请求失败: ${response.code()}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                logger.warn("节点验证失败: RPC 错误 ${rpcResponse.error.message}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "RPC 错误: ${rpcResponse.error.message}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val blockNumber = rpcResponse.result?.asString
            if (blockNumber.isNullOrBlank()) {
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "区块号为空",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            logger.info("节点验证成功: $httpUrl, 区块号: $blockNumber, 响应时间: ${responseTime}ms")
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.HEALTHY,
                message = "节点可用, 当前区块: $blockNumber",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = responseTime,
                blockNumber = blockNumber
            ))
        } catch (e: Exception) {
            logger.error("验证节点失败: ${e.message}", e)
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.UNHEALTHY,
                message = "验证失败: ${e.message}",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = null
            ))
        }
    }
}

/**
 * 添加节点请求
 */
data class AddRpcNodeRequest(
    val providerType: String,  // ALCHEMY, INFURA, QUICKNODE, CHAINSTACK, GETBLOCK, CUSTOM, PUBLIC
    val name: String,
    val apiKey: String? = null,  // 主流服务商需要
    val httpUrl: String? = null,  // CUSTOM 需要
    val wsUrl: String? = null
)

/**
 * 更新节点请求
 */
data class UpdateRpcNodeRequest(
    val id: Long,
    val name: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null
)

/**
 * 节点检查结果
 */
data class NodeCheckResult(
    val status: NodeHealthStatus,
    val message: String,
    val checkTime: Long,
    val responseTimeMs: Int?,
    val blockNumber: String? = null
)
