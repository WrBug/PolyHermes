package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.system.AddRpcNodeRequest
import com.wrbug.polymarketbot.service.system.NodeCheckResult
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.service.system.UpdateRpcNodeRequest
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * RPC 节点管理控制器
 */
@RestController
@RequestMapping("/api/system/rpc-nodes")
class RpcNodeController(
    private val rpcNodeService: RpcNodeService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(RpcNodeController::class.java)
    
    /**
     * 获取所有节点列表
     */
    @PostMapping("/list")
    fun getAllNodes(@RequestBody request: Map<String, Any>?): ResponseEntity<ApiResponse<List<RpcNodeConfigDto>>> {
        return try {
            val nodes = rpcNodeService.getAllNodes()
            val dtos = nodes.map { it.toDto() }
            ResponseEntity.ok(ApiResponse.success(dtos))
        } catch (e: Exception) {
            logger.error("获取 RPC 节点列表失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "获取节点列表失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 添加节点
     */
    @PostMapping("/add")
    fun addNode(@RequestBody request: AddRpcNodeRequest): ResponseEntity<ApiResponse<RpcNodeConfigDto>> {
        return try {
            if (request.providerType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("服务商类型不能为空"))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("节点名称不能为空"))
            }
            
            val result = rpcNodeService.addNode(request)
            
            result.fold(
                onSuccess = { node ->
                    ResponseEntity.ok(ApiResponse.success(node.toDto()))
                },
                onFailure = { e ->
                    logger.error("添加 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "添加节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("添加 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "添加节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 更新节点
     */
    @PostMapping("/update")
    fun updateNode(@RequestBody request: UpdateRpcNodeRequest): ResponseEntity<ApiResponse<RpcNodeConfigDto>> {
        return try {
            val result = rpcNodeService.updateNode(request)
            
            result.fold(
                onSuccess = { node ->
                    ResponseEntity.ok(ApiResponse.success(node.toDto()))
                },
                onFailure = { e ->
                    logger.error("更新 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "更新节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "更新节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 删除节点
     */
    @PostMapping("/delete")
    fun deleteNode(@RequestBody request: DeleteRpcNodeRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = rpcNodeService.deleteNode(request.id)
            
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "删除节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("删除 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "删除节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 更新节点优先级
     */
    @PostMapping("/update-priority")
    fun updatePriority(@RequestBody request: UpdatePriorityRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = rpcNodeService.updatePriority(request.id, request.priority)
            
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("更新节点优先级失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "更新优先级失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新节点优先级异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "更新优先级失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 检查节点健康状态
     */
    @PostMapping("/check-health")
    fun checkHealth(@RequestBody request: CheckHealthRequest): ResponseEntity<ApiResponse<Any>> {
        return try {
            if (request.id != null) {
                // 检查单个节点
                val result = rpcNodeService.checkNodeHealth(request.id)
                result.fold(
                    onSuccess = { checkResult ->
                        ResponseEntity.ok(ApiResponse.success(checkResult.toDto()))
                    },
                    onFailure = { e ->
                        logger.error("检查节点健康状态失败: ${e.message}", e)
                        ResponseEntity.ok(ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            customMsg = "检查节点失败：${e.message}",
                            messageSource = messageSource
                        ))
                    }
                )
            } else {
                // 批量检查所有节点
                val result = rpcNodeService.checkAllNodesHealth()
                result.fold(
                    onSuccess = { checkResults ->
                        val dtos = checkResults.mapValues { it.value.toDto() }
                        ResponseEntity.ok(ApiResponse.success(dtos))
                    },
                    onFailure = { e ->
                        logger.error("批量检查节点健康状态失败: ${e.message}", e)
                        ResponseEntity.ok(ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            customMsg = "批量检查节点失败：${e.message}",
                            messageSource = messageSource
                        ))
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("检查节点健康状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "检查节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 校验节点（添加前）
     */
    @PostMapping("/validate")
    fun validateNode(@RequestBody request: AddRpcNodeRequest): ResponseEntity<ApiResponse<ValidateNodeResponse>> {
        return try {
            if (request.providerType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("服务商类型不能为空"))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("节点名称不能为空"))
            }
            
            // 临时创建节点配置以进行验证（不保存到数据库）
            // 这里直接复用 addNode 的部分逻辑，但只进行校验
            val result = rpcNodeService.addNode(request)
            
            result.fold(
                onSuccess = { node ->
                    // 添加成功后立即删除（这只是为了校验）
                    rpcNodeService.deleteNode(node.id!!)
                    ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                        valid = true,
                        message = "节点可用",
                        responseTimeMs = node.responseTimeMs
                    )))
                },
                onFailure = { e ->
                    ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                        valid = false,
                        message = e.message ?: "节点验证失败",
                        responseTimeMs = null
                    )))
                }
            )
        } catch (e: Exception) {
            logger.error("验证节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                valid = false,
                message = e.message ?: "节点验证失败",
                responseTimeMs = null
            )))
        }
    }
}

/**
 * RPC 节点配置 DTO
 */
data class RpcNodeConfigDto(
    val id: Long?,
    val providerType: String,
    val name: String,
    val httpUrl: String,
    val wsUrl: String?,
    val apiKeyMasked: String?,  // 脱敏后的 API Key
    val enabled: Boolean,
    val priority: Int,
    val lastCheckTime: Long?,
    val lastCheckStatus: String?,
    val responseTimeMs: Int?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 节点检查结果 DTO
 */
data class NodeCheckResultDto(
    val status: String,
    val message: String,
    val checkTime: Long,
    val responseTimeMs: Int?,
    val blockNumber: String?
)

/**
 * 验证节点响应
 */
data class ValidateNodeResponse(
    val valid: Boolean,
    val message: String,
    val responseTimeMs: Int?
)

/**
 * 删除节点请求
 */
data class DeleteRpcNodeRequest(
    val id: Long
)

/**
 * 更新优先级请求
 */
data class UpdatePriorityRequest(
    val id: Long,
    val priority: Int
)

/**
 * 检查健康状态请求
 */
data class CheckHealthRequest(
    val id: Long? = null  // 如果为 null，则检查所有节点
)

/**
 * 扩展函数：将 RpcNodeConfig 转换为 DTO
 */
private fun RpcNodeConfig.toDto(): RpcNodeConfigDto {
    return RpcNodeConfigDto(
        id = id,
        providerType = providerType,
        name = name,
        httpUrl = httpUrl,
        wsUrl = wsUrl,
        apiKeyMasked = apiKey?.let { "***" },  // 脱敏显示
        enabled = enabled,
        priority = priority,
        lastCheckTime = lastCheckTime,
        lastCheckStatus = lastCheckStatus,
        responseTimeMs = responseTimeMs,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

/**
 * 扩展函数：将 NodeCheckResult 转换为 DTO
 */
private fun NodeCheckResult.toDto(): NodeCheckResultDto {
    return NodeCheckResultDto(
        status = status.name,
        message = message,
        checkTime = checkTime,
        responseTimeMs = responseTimeMs,
        blockNumber = blockNumber
    )
}
