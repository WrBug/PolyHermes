package com.wrbug.polymarketbot.service.nba

import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * NBA 比赛服务测试
 * 用于验证 API 调用是否正确
 */
@SpringBootTest
class NbaGameServiceTest {
    
    @Autowired
    private lateinit var retrofitFactory: RetrofitFactory
    
    @Test
    fun testNbaStatsApi() {
        runBlocking {
            try {
                val nbaStatsApi = retrofitFactory.createNbaStatsApi()
                
                // 测试获取今天的比赛
                val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                println("测试日期: $today")
                
                val response = nbaStatsApi.getScoreboard(gameDate = today)
                
                println("响应状态码: ${response.code()}")
                println("响应消息: ${response.message()}")
                
                if (response.isSuccessful && response.body() != null) {
                    val scoreboard = response.body()!!
                    println("ResultSets 数量: ${scoreboard.resultSets.size}")
                    
                    scoreboard.resultSets.forEachIndexed { index, resultSet ->
                        println("ResultSet[$index]: name=${resultSet.name}, headers=${resultSet.headers.size}, rows=${resultSet.rowSet.size}")
                        if (resultSet.headers.isNotEmpty()) {
                            println("  Headers: ${resultSet.headers.take(5)}...")
                        }
                        if (resultSet.rowSet.isNotEmpty()) {
                            println("  First row size: ${resultSet.rowSet.first().size}")
                            println("  First row: ${resultSet.rowSet.first().take(5)}...")
                        }
                    }
                } else {
                    println("API 调用失败")
                    println("错误响应体: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                println("测试异常: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

