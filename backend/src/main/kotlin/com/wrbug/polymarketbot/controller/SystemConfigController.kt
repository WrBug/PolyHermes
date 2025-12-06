package com.wrbug.polymarketbot.controller

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.SystemConfigService
import com.wrbug.polymarketbot.service.RelayClientService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 系统配置控制器
 */
@RestController
@RequestMapping("/api/system/config")
class SystemConfigController(
    private val systemConfigService: SystemConfigService,
    private val relayClientService: RelayClientService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(SystemConfigController::class.java)
    
    /**
     * 获取系统配置
     */
    @PostMapping("/get")
    fun getSystemConfig(): ResponseEntity<ApiResponse<SystemConfigDto>> {
        return try {
            val config = systemConfigService.getSystemConfig()
            ResponseEntity.ok(ApiResponse.success(config))
        } catch (e: Exception) {
            logger.error("获取系统配置失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取系统配置失败: ${e.message}", messageSource))
        }
    }
    
    /**
     * 更新 Builder API Key 配置
     */
    @PostMapping("/builder-api-key/update")
    fun updateBuilderApiKey(@RequestBody request: SystemConfigUpdateRequest): ResponseEntity<ApiResponse<SystemConfigDto>> {
        return try {
            val result = systemConfigService.updateBuilderApiKey(request)
            result.fold(
                onSuccess = { config ->
                    ResponseEntity.ok(ApiResponse.success(config))
                },
                onFailure = { e ->
                    logger.error("更新 Builder API Key 配置失败: ${e.message}", e)
                    ResponseEntity.ok(
                        ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            "更新 Builder API Key 配置失败: ${e.message}",
                            messageSource
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Builder API Key 配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "更新 Builder API Key 配置失败: ${e.message}", messageSource))
        }
    }
    
    /**
     * 检查 Builder API Key 是否已配置
     */
    @PostMapping("/builder-api-key/check")
    fun checkBuilderApiKey(): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        return try {
            val isConfigured = relayClientService.isBuilderApiKeyConfigured()
            val result = mapOf("configured" to isConfigured)
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("检查 Builder API Key 配置失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "检查 Builder API Key 配置失败: ${e.message}", messageSource))
        }
    }
    
    /**
     * 更新自动赎回配置
     */
    @PostMapping("/auto-redeem/update")
    fun updateAutoRedeem(@RequestBody request: Map<String, Boolean>): ResponseEntity<ApiResponse<SystemConfigDto>> {
        return try {
            val enabled = request["enabled"] ?: return ResponseEntity.ok(
                ApiResponse.error(ErrorCode.PARAM_ERROR, "参数错误：缺少 enabled 字段", messageSource)
            )
            val result = systemConfigService.updateAutoRedeem(enabled)
            result.fold(
                onSuccess = { config ->
                    ResponseEntity.ok(ApiResponse.success(config))
                },
                onFailure = { e ->
                    logger.error("更新自动赎回配置失败: ${e.message}", e)
                    ResponseEntity.ok(
                        ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            "更新自动赎回配置失败: ${e.message}",
                            messageSource
                        )
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("更新自动赎回配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "更新自动赎回配置失败: ${e.message}", messageSource))
        }
    }
    
    /**
     * 获取自动赎回状态
     */
    @PostMapping("/auto-redeem/status")
    fun getAutoRedeemStatus(): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        return try {
            val enabled = systemConfigService.isAutoRedeemEnabled()
            val result = mapOf("enabled" to enabled)
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("获取自动赎回状态失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取自动赎回状态失败: ${e.message}", messageSource))
        }
    }
    
}

