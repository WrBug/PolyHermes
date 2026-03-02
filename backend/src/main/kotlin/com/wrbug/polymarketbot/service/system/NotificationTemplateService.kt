package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.NotificationTemplate
import com.wrbug.polymarketbot.repository.NotificationTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 消息模板服务
 * 负责管理消息模板、渲染模板、提供变量信息
 */
@Service
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    @Lazy private val telegramNotificationService: TelegramNotificationService
) {
    private val logger = LoggerFactory.getLogger(NotificationTemplateService::class.java)

    companion object {
        // 模板类型定义
        val TEMPLATE_TYPES = mapOf(
            "ORDER_SUCCESS" to TemplateTypeInfoDto(
                type = "ORDER_SUCCESS",
                name = "订单成功通知",
                description = "订单创建成功时发送的通知"
            ),
            "ORDER_FAILED" to TemplateTypeInfoDto(
                type = "ORDER_FAILED",
                name = "订单失败通知",
                description = "订单创建失败时发送的通知"
            ),
            "ORDER_FILTERED" to TemplateTypeInfoDto(
                type = "ORDER_FILTERED",
                name = "订单过滤通知",
                description = "订单被风控过滤时发送的通知"
            ),
            "CRYPTO_TAIL_SUCCESS" to TemplateTypeInfoDto(
                type = "CRYPTO_TAIL_SUCCESS",
                name = "加密价差策略成功通知",
                description = "加密价差策略下单成功时发送的通知"
            ),
            "REDEEM_SUCCESS" to TemplateTypeInfoDto(
                type = "REDEEM_SUCCESS",
                name = "仓位赎回成功通知",
                description = "仓位赎回成功时发送的通知"
            ),
            "REDEEM_NO_RETURN" to TemplateTypeInfoDto(
                type = "REDEEM_NO_RETURN",
                name = "仓位结算（无收益）通知",
                description = "仓位结算但无收益时发送的通知"
            )
        )

        // 变量分类
        val VARIABLE_CATEGORIES = listOf(
            TemplateVariableCategoryDto("common", 0),
            TemplateVariableCategoryDto("order", 10),
            TemplateVariableCategoryDto("copy_trading", 20),
            TemplateVariableCategoryDto("redeem", 30),
            TemplateVariableCategoryDto("error", 40),
            TemplateVariableCategoryDto("filter", 50),
            TemplateVariableCategoryDto("strategy", 60)
        )

        // 各模板类型可用的变量
        val TEMPLATE_VARIABLES = mapOf(
            "ORDER_SUCCESS" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 订单变量
                TemplateVariableDto("order_id", "order", 10),
                TemplateVariableDto("market_title", "order", 11),
                TemplateVariableDto("market_link", "order", 12),
                TemplateVariableDto("side", "order", 13),
                TemplateVariableDto("outcome", "order", 14),
                TemplateVariableDto("price", "order", 15),
                TemplateVariableDto("quantity", "order", 16),
                TemplateVariableDto("amount", "order", 17),
                TemplateVariableDto("available_balance", "order", 18),
                // 跟单变量
                TemplateVariableDto("leader_name", "copy_trading", 21),
                TemplateVariableDto("config_name", "copy_trading", 22)
            ),
            "ORDER_FAILED" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 订单变量
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("side", "order", 12),
                TemplateVariableDto("outcome", "order", 13),
                TemplateVariableDto("price", "order", 14),
                TemplateVariableDto("quantity", "order", 15),
                TemplateVariableDto("amount", "order", 16),
                // 错误变量
                TemplateVariableDto("error_message", "error", 20)
            ),
            "ORDER_FILTERED" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 订单变量
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("side", "order", 12),
                TemplateVariableDto("outcome", "order", 13),
                TemplateVariableDto("price", "order", 14),
                TemplateVariableDto("quantity", "order", 15),
                TemplateVariableDto("amount", "order", 16),
                // 过滤变量
                TemplateVariableDto("filter_type", "filter", 20),
                TemplateVariableDto("filter_reason", "filter", 21)
            ),
            "CRYPTO_TAIL_SUCCESS" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 订单变量
                TemplateVariableDto("order_id", "order", 10),
                TemplateVariableDto("market_title", "order", 11),
                TemplateVariableDto("market_link", "order", 12),
                TemplateVariableDto("side", "order", 13),
                TemplateVariableDto("outcome", "order", 14),
                TemplateVariableDto("price", "order", 15),
                TemplateVariableDto("quantity", "order", 16),
                TemplateVariableDto("amount", "order", 17),
                // 策略变量
                TemplateVariableDto("strategy_name", "strategy", 20)
            ),
            "REDEEM_SUCCESS" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 赎回变量
                TemplateVariableDto("transaction_hash", "redeem", 10),
                TemplateVariableDto("total_value", "redeem", 11),
                TemplateVariableDto("available_balance", "redeem", 12)
            ),
            "REDEEM_NO_RETURN" to listOf(
                // 通用变量
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                // 赎回变量
                TemplateVariableDto("transaction_hash", "redeem", 10),
                TemplateVariableDto("available_balance", "redeem", 11)
            )
        )

        // 默认模板
        val DEFAULT_TEMPLATES = mapOf(
            "ORDER_SUCCESS" to """
🚀 <b>订单创建成功</b>

📊 <b>订单信息：</b>
• 订单ID: <code>{{order_id}}</code>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>
            """.trimIndent(),
            "ORDER_FAILED" to """
❌ <b>订单创建失败</b>

📊 <b>订单信息：</b>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⚠️ <b>错误信息：</b>
<code>{{error_message}}</code>

⏰ 时间: <code>{{time}}</code>
            """.trimIndent(),
            "ORDER_FILTERED" to """
🚫 <b>订单被过滤</b>

📊 <b>订单信息：</b>
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⚠️ <b>过滤类型：</b> <code>{{filter_type}}</code>

📝 <b>过滤原因：</b>
<code>{{filter_reason}}</code>

⏰ 时间: <code>{{time}}</code>
            """.trimIndent(),
            "CRYPTO_TAIL_SUCCESS" to """
🚀 <b>加密价差策略下单成功</b>

📊 <b>订单信息：</b>
• 订单ID: <code>{{order_id}}</code>
• 策略: {{strategy_name}}
• 市场: <a href="{{market_link}}">{{market_title}}</a>
• 市场方向: <b>{{outcome}}</b>
• 方向: <b>{{side}}</b>
• 价格: <code>{{price}}</code>
• 数量: <code>{{quantity}}</code> shares
• 金额: <code>{{amount}}</code> USDC
• 账户: {{account_name}}

⏰ 时间: <code>{{time}}</code>
            """.trimIndent(),
            "REDEEM_SUCCESS" to """
💸 <b>仓位赎回成功</b>

📊 <b>赎回信息：</b>
• 账户: {{account_name}}
• 交易哈希: <code>{{transaction_hash}}</code>
• 赎回总价值: <code>{{total_value}}</code> USDC
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>
            """.trimIndent(),
            "REDEEM_NO_RETURN" to """
📋 <b>仓位已结算（无收益）</b>

📊 <b>结算信息：</b>
<i>市场已结算，您的预测未命中，赎回价值为 0。</i>

• 账户: {{account_name}}
• 交易哈希: <code>{{transaction_hash}}</code>
• 可用余额: <code>{{available_balance}}</code> USDC

⏰ 时间: <code>{{time}}</code>
            """.trimIndent()
        )
    }

    /**
     * 获取所有模板类型
     */
    fun getTemplateTypes(): List<TemplateTypeInfoDto> {
        return TEMPLATE_TYPES.values.toList()
    }

    /**
     * 获取所有模板列表
     */
    fun getAllTemplates(): List<NotificationTemplateDto> {
        return templateRepository.findAll().map { it.toDto() }
    }

    /**
     * 获取单个模板
     */
    fun getTemplate(templateType: String): NotificationTemplateDto? {
        return templateRepository.findByTemplateType(templateType)?.toDto()
            ?: DEFAULT_TEMPLATES[templateType]?.let {
                NotificationTemplateDto(
                    templateType = templateType,
                    templateContent = it,
                    isDefault = true
                )
            }
    }

    /**
     * 获取模板可用变量
     */
    fun getTemplateVariables(templateType: String): TemplateVariablesResponse? {
        if (!TEMPLATE_TYPES.containsKey(templateType)) return null
        val variables = TEMPLATE_VARIABLES[templateType] ?: emptyList()

        // 获取使用的分类
        val usedCategories = variables.map { it.category }.toSet()
        val categories = VARIABLE_CATEGORIES.filter { usedCategories.contains(it.key) }

        return TemplateVariablesResponse(
            templateType = templateType,
            categories = categories,
            variables = variables
        )
    }

    /**
     * 更新模板
     */
    @Transactional
    fun updateTemplate(templateType: String, content: String): NotificationTemplateDto {
        val template = templateRepository.findByTemplateType(templateType)
        val now = System.currentTimeMillis()

        return if (template != null) {
            template.templateContent = content
            template.isDefault = false
            template.updatedAt = now
            templateRepository.save(template).toDto()
        } else {
            val newTemplate = NotificationTemplate(
                templateType = templateType,
                templateContent = content,
                isDefault = false,
                createdAt = now,
                updatedAt = now
            )
            templateRepository.save(newTemplate).toDto()
        }
    }

    /**
     * 重置模板为默认
     */
    @Transactional
    fun resetTemplate(templateType: String): NotificationTemplateDto? {
        val defaultContent = DEFAULT_TEMPLATES[templateType] ?: return null
        val template = templateRepository.findByTemplateType(templateType)
        val now = System.currentTimeMillis()

        return if (template != null) {
            template.templateContent = defaultContent
            template.isDefault = true
            template.updatedAt = now
            templateRepository.save(template).toDto()
        } else {
            val newTemplate = NotificationTemplate(
                templateType = templateType,
                templateContent = defaultContent,
                isDefault = true,
                createdAt = now,
                updatedAt = now
            )
            templateRepository.save(newTemplate).toDto()
        }
    }

    /**
     * 渲染模板（按类型取模板内容后替换变量）
     */
    fun renderTemplate(templateType: String, variables: Map<String, String>): String {
        val template = getTemplate(templateType)
        val content = template?.templateContent ?: DEFAULT_TEMPLATES[templateType] ?: ""
        return renderTemplateContent(content, variables)
    }

    /**
     * 对给定模板内容做变量替换（不查库）
     */
    fun renderTemplateContent(content: String, variables: Map<String, String>): String {
        var result = content
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        result = result.replace(Regex("\\{\\{[^}]+}}"), "-")
        return result
    }

    /**
     * 发送测试消息
     */
    suspend fun sendTestMessage(templateType: String, content: String? = null): Boolean {
        val templateContent = content ?: getTemplate(templateType)?.templateContent ?: return false
        val testVariables = generateTestVariables(templateType)
        val message = renderTemplateContent(templateContent, testVariables)
        return try {
            telegramNotificationService.sendMessage(message)
            true
        } catch (e: Exception) {
            logger.error("发送测试消息失败: ${e.message}", e)
            false
        }
    }

    /**
     * 生成测试变量数据
     */
    private fun generateTestVariables(templateType: String): Map<String, String> {
        return mapOf(
            "account_name" to "测试账户",
            "wallet_address" to "0x1234...5678",
            "time" to "2024-01-15 12:30:00",
            "order_id" to "12345678",
            "market_title" to "测试市场标题",
            "market_link" to "https://polymarket.com/event/test",
            "side" to "买入",
            "outcome" to "YES",
            "price" to "0.55",
            "quantity" to "100",
            "amount" to "55.00",
            "available_balance" to "1000.00",
            "leader_name" to "测试Leader",
            "config_name" to "测试配置",
            "error_message" to "余额不足",
            "filter_type" to "价差过大",
            "filter_reason" to "当前市场价差为 5%，超过设定的 3% 限制",
            "strategy_name" to "BTC价差策略",
            "transaction_hash" to "0xabcd...efgh",
            "total_value" to "100.00"
        )
    }

    /**
     * Entity 转 DTO
     */
    private fun NotificationTemplate.toDto() = NotificationTemplateDto(
        id = id,
        templateType = templateType,
        templateContent = templateContent,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
