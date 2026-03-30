package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 消息推送配置控制器
 */
@RestController
@RequestMapping("/api/system/notifications")
class NotificationController(
    private val notificationConfigService: NotificationConfigService,
    private val telegramNotificationService: TelegramNotificationService,
    private val notificationTemplateService: NotificationTemplateService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(NotificationController::class.java)
    
    /**
     * 获取所有配置
     */
    @PostMapping("/configs/list")
    fun getConfigs(@RequestBody request: NotificationConfigListRequest?): ResponseEntity<ApiResponse<List<NotificationConfigDto>>> {
        return try {
            val configs = runBlocking {
                if (request?.type != null) {
                    notificationConfigService.getConfigsByType(request.type)
                } else {
                    notificationConfigService.getAllConfigs()
                }
            }
            ResponseEntity.ok(ApiResponse.success(configs))
        } catch (e: Exception) {
            logger.error("获取通知配置列表失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_FETCH_FAILED,
                customMsg = "获取配置列表失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 获取配置详情
     */
    @PostMapping("/configs/detail")
    fun getConfigDetail(@RequestBody request: NotificationConfigDetailRequest): ResponseEntity<ApiResponse<NotificationConfigDto>> {
        return try {
            if (request.id == null) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_ID_EMPTY,
                    messageSource = messageSource
                ))
            }
            
            val config = runBlocking {
                notificationConfigService.getConfigById(request.id)
            }
            
            if (config == null) {
                ResponseEntity.ok(ApiResponse.error(ErrorCode.NOT_FOUND, messageSource = messageSource))
            } else {
                ResponseEntity.ok(ApiResponse.success(config))
            }
        } catch (e: Exception) {
            logger.error("获取通知配置详情失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_FETCH_FAILED,
                customMsg = "获取配置详情失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 创建配置
     */
    @PostMapping("/configs/create")
    fun createConfig(@RequestBody request: NotificationConfigRequest): ResponseEntity<ApiResponse<NotificationConfigDto>> {
        return try {
            if (request.type.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("推送类型不能为空"))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("配置名称不能为空"))
            }
            if (request.config.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.paramError("配置信息不能为空"))
            }
            
            val result = runBlocking {
                notificationConfigService.createConfig(request)
            }
            
            result.fold(
                onSuccess = { config ->
                    ResponseEntity.ok(ApiResponse.success(config))
                },
                onFailure = { e ->
                    logger.error("创建通知配置失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.NOTIFICATION_CONFIG_CREATE_FAILED,
                        customMsg = "创建配置失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("创建通知配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_CREATE_FAILED,
                customMsg = "创建配置失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 更新配置
     */
    @PostMapping("/configs/update")
    fun updateConfig(@RequestBody request: NotificationConfigUpdateRequest): ResponseEntity<ApiResponse<NotificationConfigDto>> {
        return try {
            if (request.id == null) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_ID_EMPTY,
                    messageSource = messageSource
                ))
            }
            if (request.type.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_TYPE_EMPTY,
                    messageSource = messageSource
                ))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_NAME_EMPTY,
                    messageSource = messageSource
                ))
            }
            if (request.config.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_DATA_EMPTY,
                    messageSource = messageSource
                ))
            }
            
            val configRequest = NotificationConfigRequest(
                type = request.type,
                name = request.name,
                enabled = request.enabled,
                config = request.config
            )
            
            val result = runBlocking {
                notificationConfigService.updateConfig(request.id, configRequest)
            }
            
            result.fold(
                onSuccess = { config ->
                    ResponseEntity.ok(ApiResponse.success(config))
                },
                onFailure = { e ->
                    logger.error("更新通知配置失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.NOTIFICATION_CONFIG_UPDATE_FAILED,
                        customMsg = "更新配置失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新通知配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_UPDATE_FAILED,
                customMsg = "更新配置失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 更新启用状态
     */
    @PostMapping("/configs/update-enabled")
    fun updateEnabled(@RequestBody request: NotificationConfigUpdateEnabledRequest): ResponseEntity<ApiResponse<NotificationConfigDto>> {
        return try {
            if (request.id == null) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_ID_EMPTY,
                    messageSource = messageSource
                ))
            }
            
            val result = runBlocking {
                notificationConfigService.updateEnabled(request.id, request.enabled ?: true)
            }
            
            result.fold(
                onSuccess = { config ->
                    ResponseEntity.ok(ApiResponse.success(config))
                },
                onFailure = { e ->
                    logger.error("更新通知配置启用状态失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.NOTIFICATION_CONFIG_UPDATE_ENABLED_FAILED,
                        customMsg = "更新启用状态失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新通知配置启用状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_UPDATE_ENABLED_FAILED,
                customMsg = "更新启用状态失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 删除配置
     */
    @PostMapping("/configs/delete")
    fun deleteConfig(@RequestBody request: NotificationConfigDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.id == null) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_ID_EMPTY,
                    messageSource = messageSource
                ))
            }
            
            val result = runBlocking {
                notificationConfigService.deleteConfig(request.id)
            }
            
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除通知配置失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.NOTIFICATION_CONFIG_DELETE_FAILED,
                        customMsg = "删除配置失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("删除通知配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_CONFIG_DELETE_FAILED,
                customMsg = "删除配置失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 测试通知
     */
    @PostMapping("/test")
    fun testNotification(@RequestBody request: TestNotificationRequest?): ResponseEntity<ApiResponse<Boolean>> {
        return try {
            val message = request?.message ?: "这是一条测试消息"
            val success = runBlocking {
                telegramNotificationService.sendTestMessage(message)
            }
            
            if (success) {
                ResponseEntity.ok(ApiResponse.success(true))
            } else {
                ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_TEST_FAILED,
                    messageSource = messageSource
                ))
            }
        } catch (e: Exception) {
            logger.error("测试通知失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_TEST_FAILED,
                customMsg = "测试通知失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    /**
     * 获取 Telegram Chat IDs
     */
    @PostMapping("/telegram/get-chat-ids")
    fun getTelegramChatIds(@RequestBody request: GetTelegramChatIdsRequest): ResponseEntity<ApiResponse<List<String>>> {
        return try {
            if (request.botToken.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_CONFIG_BOT_TOKEN_EMPTY,
                    messageSource = messageSource
                ))
            }
            
            val result = runBlocking {
                telegramNotificationService.getChatIds(request.botToken)
            }
            
            result.fold(
                onSuccess = { chatIds ->
                    ResponseEntity.ok(ApiResponse.success(chatIds))
                },
                onFailure = { e ->
                    logger.error("获取 Chat IDs 失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.NOTIFICATION_GET_CHAT_IDS_FAILED,
                        customMsg = "获取 Chat IDs 失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("获取 Chat IDs 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_GET_CHAT_IDS_FAILED,
                customMsg = "获取 Chat IDs 失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }

    // ==================== 模板相关 API ====================

    /**
     * 获取所有模板类型
     */
    @PostMapping("/templates/types")
    fun getTemplateTypes(): ResponseEntity<ApiResponse<List<TemplateTypeInfoDto>>> {
        return try {
            val types = notificationTemplateService.getTemplateTypes()
            ResponseEntity.ok(ApiResponse.success(types))
        } catch (e: Exception) {
            logger.error("获取模板类型失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 获取所有模板
     */
    @PostMapping("/templates/list")
    fun getTemplates(): ResponseEntity<ApiResponse<List<NotificationTemplateDto>>> {
        return try {
            val templates = notificationTemplateService.getAllTemplates()
            ResponseEntity.ok(ApiResponse.success(templates))
        } catch (e: Exception) {
            logger.error("获取模板列表失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 获取单个模板
     */
    @PostMapping("/templates/detail")
    fun getTemplateDetail(@RequestBody request: TemplateDetailRequest): ResponseEntity<ApiResponse<NotificationTemplateDto>> {
        return try {
            if (request.templateType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板类型不能为空"))
            }

            val template = notificationTemplateService.getTemplate(request.templateType)
            if (template == null) {
                ResponseEntity.ok(ApiResponse.error(ErrorCode.NOT_FOUND, messageSource = messageSource))
            } else {
                ResponseEntity.ok(ApiResponse.success(template))
            }
        } catch (e: Exception) {
            logger.error("获取模板详情失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 获取模板可用变量
     */
    @PostMapping("/templates/variables")
    fun getTemplateVariables(@RequestBody request: TemplateDetailRequest): ResponseEntity<ApiResponse<TemplateVariablesResponse>> {
        return try {
            if (request.templateType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板类型不能为空"))
            }

            val variables = notificationTemplateService.getTemplateVariables(request.templateType)
            if (variables == null) {
                ResponseEntity.ok(ApiResponse.error(ErrorCode.NOT_FOUND, messageSource = messageSource))
            } else {
                ResponseEntity.ok(ApiResponse.success(variables))
            }
        } catch (e: Exception) {
            logger.error("获取模板变量失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 更新模板
     */
    @PostMapping("/templates/update")
    fun updateTemplate(@RequestBody request: UpdateTemplateRequestWithId): ResponseEntity<ApiResponse<NotificationTemplateDto>> {
        return try {
            if (request.templateType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板类型不能为空"))
            }
            if (request.templateContent.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板内容不能为空"))
            }

            val template = notificationTemplateService.updateTemplate(request.templateType, request.templateContent)
            ResponseEntity.ok(ApiResponse.success(template))
        } catch (e: Exception) {
            logger.error("更新模板失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 重置模板为默认
     */
    @PostMapping("/templates/reset")
    fun resetTemplate(@RequestBody request: TemplateDetailRequest): ResponseEntity<ApiResponse<NotificationTemplateDto>> {
        return try {
            if (request.templateType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板类型不能为空"))
            }

            val template = notificationTemplateService.resetTemplate(request.templateType)
            if (template == null) {
                ResponseEntity.ok(ApiResponse.error(ErrorCode.NOT_FOUND, messageSource = messageSource))
            } else {
                ResponseEntity.ok(ApiResponse.success(template))
            }
        } catch (e: Exception) {
            logger.error("重置模板失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }

    /**
     * 发送模板测试消息
     */
    @PostMapping("/templates/test")
    fun testTemplate(@RequestBody request: TestTemplateRequest): ResponseEntity<ApiResponse<Boolean>> {
        return try {
            if (request.templateType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板类型不能为空"))
            }

            val success = runBlocking {
                notificationTemplateService.sendTestMessage(request.templateType, request.templateContent)
            }

            if (success) {
                ResponseEntity.ok(ApiResponse.success(true))
            } else {
                ResponseEntity.ok(ApiResponse.error(
                    ErrorCode.NOTIFICATION_TEST_FAILED,
                    messageSource = messageSource
                ))
            }
        } catch (e: Exception) {
            logger.error("发送模板测试消息失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.NOTIFICATION_TEST_FAILED,
                customMsg = "发送测试消息失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
}

/**
 * 获取 Telegram Chat IDs 请求
 */
data class GetTelegramChatIdsRequest(
    val botToken: String
)

/**
 * 配置列表请求
 */
data class NotificationConfigListRequest(
    val type: String? = null  // 可选，按类型筛选
)

/**
 * 配置详情请求
 */
data class NotificationConfigDetailRequest(
    val id: Long
)

/**
 * 配置更新请求
 */
data class NotificationConfigUpdateRequest(
    val id: Long,
    val type: String,
    val name: String,
    val enabled: Boolean? = null,
    val config: Map<String, Any>
)

/**
 * 更新启用状态请求
 */
data class NotificationConfigUpdateEnabledRequest(
    val id: Long,
    val enabled: Boolean? = true
)

/**
 * 删除配置请求
 */
data class NotificationConfigDeleteRequest(
    val id: Long
)

/**
 * 模板详情请求
 */
data class TemplateDetailRequest(
    val templateType: String
)

/**
 * 更新模板请求（带类型）
 */
data class UpdateTemplateRequestWithId(
    val templateType: String,
    val templateContent: String
)

