package com.wrbug.polymarketbot.dto

/**
 * 消息模板 DTO
 */
data class NotificationTemplateDto(
    val id: Long? = null,
    val templateType: String,  // 模板类型
    val templateContent: String,  // 模板内容
    val isDefault: Boolean = false,  // 是否使用默认模板
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

/**
 * 模板变量 DTO
 */
data class TemplateVariableDto(
    val key: String,  // 变量名，如 account_name
    val category: String,  // 分类：common, order, copy_trading, redeem, error
    val sortOrder: Int = 0  // 排序顺序
)

/**
 * 模板变量分类 DTO
 */
data class TemplateVariableCategoryDto(
    val key: String,  // 分类 key
    val sortOrder: Int = 0  // 排序顺序
)

/**
 * 模板变量列表响应
 */
data class TemplateVariablesResponse(
    val templateType: String,  // 模板类型
    val categories: List<TemplateVariableCategoryDto>,  // 分类列表
    val variables: List<TemplateVariableDto>  // 变量列表
)

/**
 * 更新模板请求
 */
data class UpdateTemplateRequest(
    val templateContent: String  // 模板内容
)

/**
 * 测试模板请求
 */
data class TestTemplateRequest(
    val templateType: String,  // 模板类型
    val templateContent: String? = null  // 可选，如果不提供则使用已保存的模板
)

/**
 * 模板类型信息
 */
data class TemplateTypeInfoDto(
    val type: String,  // 模板类型
    val name: String,  // 类型名称
    val description: String  // 类型描述
)
