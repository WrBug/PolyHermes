package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * JSON 工具类
 * 用于解析 JSON 字符串
 */
object JsonUtils {
    
    private val gson = Gson()
    
    /**
     * 解析 JSON 字符串数组
     * @param jsonString JSON 字符串，如 "[\"Yes\", \"No\"]"
     * @return 字符串列表，如果解析失败返回空列表
     */
    fun parseStringArray(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }
        
        return try {
            val listType = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(jsonString, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 解析 JSON 字符串列表（parseStringArray 的别名）
     */
    fun parseStringList(jsonString: String?): List<String> {
        return parseStringArray(jsonString)
    }
    
    /**
     * 将对象转换为 JSON 字符串
     * @param obj 要转换的对象
     * @return JSON 字符串
     */
    fun toJson(obj: Any?): String? {
        if (obj == null) {
            return null
        }
        
        return try {
            gson.toJson(obj)
        } catch (e: Exception) {
            null
        }
    }
}

