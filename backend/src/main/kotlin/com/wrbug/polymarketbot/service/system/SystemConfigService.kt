package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.dto.SystemConfigUpdateRequest
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 系统配置服务
 */
@Service
class SystemConfigService(
    private val systemConfigRepository: SystemConfigRepository,
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(SystemConfigService::class.java)

    companion object {
        const val CONFIG_KEY_BUILDER_API_KEY = "builder.api_key"
        const val CONFIG_KEY_BUILDER_SECRET = "builder.secret"
        const val CONFIG_KEY_BUILDER_PASSPHRASE = "builder.passphrase"
        const val CONFIG_KEY_AUTO_REDEEM = "auto_redeem"

        /**
         * 遮蔽敏感信息，仅显示前4位和后4位
         * 例如：abcd1234...wxyz5678
         */
        fun maskSensitiveValue(value: String?): String? {
            if (value == null) return null
            return when {
                value.length <= 8 -> "****"  // 太短则完全遮蔽
                value.length <= 16 -> "${value.take(2)}...${value.takeLast(2)}"
                else -> "${value.take(4)}...${value.takeLast(4)}"
            }
        }
    }

    /**
     * 获取系统配置
     */
    fun getSystemConfig(): SystemConfigDto {
        val builderApiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val builderSecret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val builderPassphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        val autoRedeem = isAutoRedeemEnabled()

        // 获取遮蔽后的显示值（仅显示部分字符，用于前端确认配置）
        val builderApiKeyDisplay = builderApiKey?.let {
            try {
                maskSensitiveValue(cryptoUtils.decrypt(it))
            } catch (e: Exception) {
                null
            }
        }

        val builderSecretDisplay = builderSecret?.let {
            try {
                maskSensitiveValue(cryptoUtils.decrypt(it))
            } catch (e: Exception) {
                null
            }
        }

        val builderPassphraseDisplay = builderPassphrase?.let {
            try {
                maskSensitiveValue(cryptoUtils.decrypt(it))
            } catch (e: Exception) {
                null
            }
        }

        return SystemConfigDto(
            builderApiKeyConfigured = builderApiKey != null,
            builderSecretConfigured = builderSecret != null,
            builderPassphraseConfigured = builderPassphrase != null,
            builderApiKeyDisplay = builderApiKeyDisplay,
            builderSecretDisplay = builderSecretDisplay,
            builderPassphraseDisplay = builderPassphraseDisplay,
            autoRedeemEnabled = autoRedeem
        )
    }

    /**
     * 更新 Builder API Key 配置
     */
    @Transactional
    fun updateBuilderApiKey(request: SystemConfigUpdateRequest): Result<SystemConfigDto> {
        return try {
            // 更新 Builder API Key
            if (request.builderApiKey != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_API_KEY,
                    if (request.builderApiKey.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderApiKey)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Secret
            if (request.builderSecret != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_SECRET,
                    if (request.builderSecret.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderSecret)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Passphrase
            if (request.builderPassphrase != null) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_PASSPHRASE,
                    if (request.builderPassphrase.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderPassphrase)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新自动赎回配置
            if (request.autoRedeem != null) {
                updateConfigValue(
                    CONFIG_KEY_AUTO_REDEEM,
                    request.autoRedeem.toString()
                )
            }

            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新系统配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取配置值（解密）
     */
    fun getBuilderApiKey(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_API_KEY)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderSecret(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_SECRET)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderPassphrase(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)?.let { cryptoUtils.decrypt(it) }
    }

    /**
     * 检查 Builder API Key 是否已配置
     */
    fun isBuilderApiKeyConfigured(): Boolean {
        val apiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val secret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val passphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        return apiKey != null && secret != null && passphrase != null
    }

    /**
     * 检查自动赎回是否启用
     */
    fun isAutoRedeemEnabled(): Boolean {
        val autoRedeemValue = getConfigValue(CONFIG_KEY_AUTO_REDEEM)
        return when (autoRedeemValue?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> false  // 默认开启
        }
    }

    /**
     * 更新自动赎回配置
     */
    @Transactional
    fun updateAutoRedeem(enabled: Boolean): Result<SystemConfigDto> {
        return try {
            updateConfigValue(CONFIG_KEY_AUTO_REDEEM, enabled.toString())
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新自动赎回配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取配置值（原始值，加密存储）
     */
    private fun getConfigValue(configKey: String): String? {
        return systemConfigRepository.findByConfigKey(configKey)?.configValue
    }

    /**
     * 更新配置值
     */
    private fun updateConfigValue(configKey: String, configValue: String?) {
        val existing = systemConfigRepository.findByConfigKey(configKey)
        if (existing != null) {
            val updated = existing.copy(
                configValue = configValue,
                updatedAt = System.currentTimeMillis()
            )
            systemConfigRepository.save(updated)
        } else {
            val newConfig = SystemConfig(
                configKey = configKey,
                configValue = configValue,
                description = when (configKey) {
                    CONFIG_KEY_BUILDER_API_KEY -> "Builder API Key（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_SECRET -> "Builder Secret（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_PASSPHRASE -> "Builder Passphrase（用于 Gasless 交易）"
                    CONFIG_KEY_AUTO_REDEEM -> "自动赎回（系统级别配置，默认开启）"
                    else -> null
                }
            )
            systemConfigRepository.save(newConfig)
        }
    }
}

