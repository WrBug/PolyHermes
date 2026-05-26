package com.wrbug.polymarketbot.service.system

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.GeoblockCheckDto
import com.wrbug.polymarketbot.dto.PolymarketGeoblockApiResponse
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class GeoblockService(
    private val gson: Gson
) {

    private val logger = LoggerFactory.getLogger(GeoblockService::class.java)

    companion object {
        private const val GEOBLOCK_URL = "https://polymarket.com/api/geoblock"
    }

    suspend fun checkGeoblock(): Result<GeoblockCheckDto> = withContext(Dispatchers.IO) {
        val client = createClient()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        checkGeoblockWithClient(client)
    }

    fun checkGeoblockWithClient(client: OkHttpClient): Result<GeoblockCheckDto> {
        return try {
            val request = Request.Builder()
                .url(GEOBLOCK_URL)
                .get()
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(
                        IllegalStateException("HTTP ${response.code}: ${response.message}")
                    )
                }
                val body = response.body?.string()
                    ?: return Result.failure(IllegalStateException("Empty response body"))
                val apiResponse = gson.fromJson(body, PolymarketGeoblockApiResponse::class.java)
                Result.success(
                    GeoblockCheckDto(
                        blocked = apiResponse.blocked,
                        ip = apiResponse.ip,
                        country = apiResponse.country,
                        region = apiResponse.region,
                        checkedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn("Geoblock 检查失败", e)
            Result.failure(e)
        }
    }
}
