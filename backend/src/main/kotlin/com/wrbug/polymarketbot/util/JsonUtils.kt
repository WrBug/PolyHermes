package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * JSON 工具类
 * 用于初始化全局 Gson 实例，供扩展函数使用
 *
 * 使用 Spring 注入的 Gson Bean（已在 GsonConfig 中配置为 lenient 模式）
 * 初始化后设置全局 gson 实例，供扩展函数使用
 */
@Component
class JsonUtils(
    private val injectedGson: Gson
) {

    @PostConstruct
    fun init() {
        // 设置全局 Gson 实例，供扩展函数使用
        _gson = injectedGson
    }

    /**
     * 解析 JSON 字符串数组
     * @param jsonString JSON 字符串，如 "[\"Yes\", \"No\"]"
     * @return 字符串列表，如果解析失败返回空列表
     */
    fun parseStringArray(jsonString: String?): List<String> {
        return jsonString?.parseStringArray() ?: emptyList()
    }
}

/**
 * 全局 Gson 实例（用于扩展函数）
 * 在 JsonUtils 初始化时设置
 */
@Volatile
private var _gson: Gson? = null

/**
 * 获取全局 Gson 实例
 * 如果尚未初始化，创建默认实例
 */
val gson: Gson
    get() = _gson ?: GsonBuilder().setLenient().create().also { _gson = it }

// ============================================================================
// 扩展函数：JSON 序列化和反序列化
// ============================================================================

/**
 * 将对象转换为 JSON 字符串
 *
 * @return JSON 字符串，如果对象为 null 则返回空字符串
 *
 * @example
 * ```kotlin
 * val obj = MyDataClass(name = "test", value = 123)
 * val json = obj.toJson()  // {"name":"test","value":123}
 * ```
 */
fun Any?.toJson(): String {
    return if (this == null) {
        ""
    } else {
        try {
            gson.toJson(this)
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * 将 JSON 字符串解析为对象（支持泛型）
 *
 * @return 解析后的对象，如果解析失败返回 null
 *
 * @example
 * ```kotlin
 * val json = "{\"name\":\"test\",\"value\":123}"
 * val obj = json.fromJson<MyDataClass>()  // MyDataClass(name="test", value=123)
 *
 * // 对于泛型类型，自动使用 TypeToken
 * val json = "[\"a\", \"b\", \"c\"]"
 * val list = json.fromJson<List<String>>()  // ["a", "b", "c"]
 * ```
 */
inline fun <reified T> String?.fromJson(): T? {
    if (this.isNullOrBlank()) {
        return null
    }

    return try {
        val typeToken = object : TypeToken<T>() {}
        gson.fromJson(this, typeToken.type)
    } catch (e: Exception) {
        null
    }
}

/**
 * 将 JsonElement 解析为对象（支持泛型）
 *
 * @return 解析后的对象，如果解析失败返回 null
 *
 * @example
 * ```kotlin
 * val jsonElement: JsonElement = ...
 * val obj = jsonElement.fromJson<MyDataClass>()
 * ```
 */
inline fun <reified T> JsonElement?.fromJson(): T? {
    if (this == null) {
        return null
    }

    return try {
        val typeToken = object : TypeToken<T>() {}
        gson.fromJson(this, typeToken.type)
    } catch (e: Exception) {
        null
    }
}

/**
 * 解析 JSON 字符串数组
 *
 * @return 字符串列表，如果解析失败返回空列表
 *
 * @example
 * ```kotlin
 * val json = "[\"Yes\", \"No\"]"
 * val list = json.parseStringArray()  // ["Yes", "No"]
 * ```
 */
fun String?.parseStringArray(): List<String> {
    if (this.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        val listType = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(this, listType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

