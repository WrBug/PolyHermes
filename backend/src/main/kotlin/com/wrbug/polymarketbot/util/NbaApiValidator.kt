package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.api.NbaStatsApi
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * NBA API 验证工具
 * 用于验证 API 调用是否正确
 */
object NbaApiValidator {
    private val logger = LoggerFactory.getLogger(NbaApiValidator::class.java)
    
    /**
     * 验证 API 调用
     */
    suspend fun validateApi(nbaStatsApi: NbaStatsApi): Boolean {
        return try {
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            logger.info("验证 NBA Stats API，日期: $today")
            
            val response = nbaStatsApi.getScoreboard(gameDate = today)
            
            logger.info("API 响应状态码: ${response.code()}")
            logger.info("API 响应消息: ${response.message()}")
            
            if (response.isSuccessful && response.body() != null) {
                val scoreboard = response.body()!!
                logger.info("ResultSets 数量: ${scoreboard.resultSets.size}")
                
                scoreboard.resultSets.forEachIndexed { index, resultSet ->
                    logger.info("ResultSet[$index]: name=${resultSet.name}, headers=${resultSet.headers.size}, rows=${resultSet.rowSet.size}")
                    if (resultSet.headers.isNotEmpty()) {
                        logger.info("  Headers: ${resultSet.headers.take(10)}")
                    }
                    if (resultSet.rowSet.isNotEmpty()) {
                        val firstRow = resultSet.rowSet.first()
                        logger.info("  First row size: ${firstRow.size}")
                        logger.info("  First row (first 5): ${firstRow.take(5)}")
                    }
                }
                
                // 检查是否有 GameHeader 和 LineScore
                val hasGameHeader = scoreboard.resultSets.any { it.name == "GameHeader" }
                val hasLineScore = scoreboard.resultSets.any { it.name == "LineScore" }
                
                logger.info("包含 GameHeader: $hasGameHeader")
                logger.info("包含 LineScore: $hasLineScore")
                
                hasGameHeader && hasLineScore
            } else {
                logger.error("API 调用失败")
                val errorBody = response.errorBody()?.string()
                logger.error("错误响应体: $errorBody")
                false
            }
        } catch (e: Exception) {
            logger.error("验证 API 异常: ${e.message}", e)
            false
        }
    }
}

