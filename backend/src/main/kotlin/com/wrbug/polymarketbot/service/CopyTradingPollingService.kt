package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * 跟单轮询监听服务
 * 通过定期轮询 Polymarket Data API 的 /activity 接口获取Leader的交易记录
 * 使用 /activity 接口可以查询用户的链上活动，包括交易
 */
@Service
class CopyTradingPollingService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val retrofitFactory: RetrofitFactory,
    private val templateRepository: CopyTradingTemplateRepository
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingPollingService::class.java)
    
    @Value("\${copy.trading.polling.interval:2000}")
    private var pollingInterval: Long = 2000  // 轮询间隔（毫秒），默认2秒
    
    @Value("\${copy.trading.polling.enabled:true}")
    private var pollingEnabled: Boolean = true  // 是否启用轮询
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 存储需要监听的Leader：leaderId -> Leader
    private val monitoredLeaders = ConcurrentHashMap<Long, Leader>()
    
    // 存储每个Leader已缓存的交易ID集合：leaderId -> Set<tradeId>
    private val cachedTradeIds = ConcurrentHashMap<Long, MutableSet<String>>()
    
    // 存储每个Leader是否首次轮询：leaderId -> isFirstPoll
    private val isFirstPoll = ConcurrentHashMap<Long, Boolean>()
    
    // 轮询任务
    private var pollingJob: Job? = null
    
    /**
     * 启动轮询监听
     */
    fun start(leaders: List<Leader>) {
        if (!pollingEnabled) {
            return
        }
        
        leaders.forEach { leader ->
            addLeader(leader)
        }
        
        // 启动轮询任务
        startPolling()
    }
    
    /**
     * 添加Leader监听
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID为空，跳过: ${leader.leaderAddress}")
            return
        }
        
        val leaderId = leader.id!!
        monitoredLeaders[leaderId] = leader
        // 初始化缓存的交易ID集合
        cachedTradeIds[leaderId] = mutableSetOf()
        // 首次轮询标志，用于缓存数据而不处理
        isFirstPoll[leaderId] = true
        
        // 如果轮询任务没有运行，启动它
        startPolling()
    }
    
    /**
     * 移除Leader监听
     */
    fun removeLeader(leaderId: Long) {
        monitoredLeaders.remove(leaderId)
        cachedTradeIds.remove(leaderId)
        isFirstPoll.remove(leaderId)
        
        // 如果没有需要监听的Leader了，停止轮询任务
        if (monitoredLeaders.isEmpty()) {
            stopPolling()
        }
    }
    
    /**
     * 停止所有监听
     */
    fun stop() {
        stopPolling()
        monitoredLeaders.clear()
        cachedTradeIds.clear()
        isFirstPoll.clear()
    }
    
    /**
     * 启动轮询任务
     */
    private fun startPolling() {
        if (pollingJob != null && pollingJob!!.isActive) {
            return
        }
        
        if (monitoredLeaders.isEmpty()) {
            return
        }
        
        pollingJob = scope.launch {
            
            while (isActive) {
                try {
                    // 轮询所有Leader的交易
                    pollAllLeaders()
                    
                    // 等待下一次轮询
                    delay(pollingInterval)
                } catch (e: Exception) {
                    logger.error("轮询任务异常", e)
                    delay(pollingInterval)  // 异常后继续等待
                }
            }
        }
    }
    
    /**
     * 停止轮询任务
     */
    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * 轮询所有Leader的交易
     */
    private suspend fun pollAllLeaders() {
        val leaders = monitoredLeaders.values.toList()
        
        if (leaders.isEmpty()) {
            return
        }
        
        // 并发轮询所有Leader（限制并发数）
        leaders.chunked(10).forEach { chunk ->
            chunk.forEach { leader ->
                try {
                    pollLeaderTrades(leader)
                } catch (e: Exception) {
                    logger.error("轮询Leader交易失败: leaderId=${leader.id}, address=${leader.leaderAddress}", e)
                }
            }
            // 每个chunk之间稍作延迟，避免API限流
            delay(100)
        }
    }
    
    /**
     * 轮询单个Leader的交易
     * 使用 Polymarket Data API 的 /activity 接口
     * 通过 diff 分析增量数据，不使用 start 字段
     */
    private suspend fun pollLeaderTrades(leader: Leader) {
        if (leader.id == null) {
            return
        }
        
        val leaderId = leader.id!!
        val leaderAddress = leader.leaderAddress
        
        try {
            val firstPoll = isFirstPoll[leaderId] == true
            val cachedIds = cachedTradeIds[leaderId] ?: mutableSetOf()
            
            // 创建 Data API 客户端（不需要认证）
            val dataApi = retrofitFactory.createDataApi()
            
            // 查询用户活动（只查询交易类型，不使用 start 字段）
            // 查询最近的数据（limit=100），通过 diff 找出增量
            val response: Response<List<UserActivityResponse>> = dataApi.getUserActivity(
                user = leaderAddress,
                limit = 100,  // 每次最多查询100条
                offset = 0,
                type = listOf("TRADE"),  // 只查询交易类型
                start = null,  // 不使用 start 字段
                sortBy = "TIMESTAMP",
                sortDirection = "DESC"  // 按时间戳降序，最新的在前
            )
            
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("获取Leader活动失败: leaderId=$leaderId, address=$leaderAddress, code=${response.code()}, message=${response.message()}")
                return
            }
            
            val activities = response.body()!!
            
            // 将 UserActivityResponse 转换为 TradeResponse
            val allTrades = activities.mapNotNull { activity ->
                // 只处理交易类型
                if (activity.type != "TRADE" || activity.side == null || activity.price == null || activity.size == null) {
                    return@mapNotNull null
                }
                
                // 转换为 TradeResponse
                TradeResponse(
                    id = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}",
                    market = activity.conditionId,
                    side = activity.side,  // BUY 或 SELL
                    price = activity.price.toString(),
                    size = activity.size.toString(),
                    timestamp = activity.timestamp.toString(),  // 时间戳（秒）
                    user = activity.proxyWallet,
                    outcomeIndex = activity.outcomeIndex,  // 结果索引（0=YES, 1=NO）
                    outcome = activity.outcome  // 结果名称
                )
            }
            
            if (firstPoll) {
                // 首次轮询：缓存所有查询到的交易ID，不处理
                val tradeIds = allTrades.map { it.id }.toSet()
                cachedIds.addAll(tradeIds)
                cachedTradeIds[leaderId] = cachedIds
                
                // 标记首次轮询完成
                isFirstPoll[leaderId] = false
            } else {
                // 后续轮询：通过 diff 找出新增的交易
                val newTradeIds = allTrades.map { it.id }.toSet()
                val incrementalTradeIds = newTradeIds - cachedIds
                
                if (incrementalTradeIds.isNotEmpty()) {
                    // 找出新增的交易
                    val incrementalTrades = allTrades.filter { it.id in incrementalTradeIds }
                    
                    
                    // 处理新增的交易
                    incrementalTrades.forEach { trade ->
                        try {
                            // 检查是否已处理（去重由processTrade内部处理）
                            copyOrderTrackingService.processTrade(leaderId, trade, "polling")
                        } catch (e: Exception) {
                            logger.error("处理交易失败: leaderId=$leaderId, tradeId=${trade.id}", e)
                        }
                    }
                    
                    // 更新缓存：添加新增的交易ID
                    cachedIds.addAll(incrementalTradeIds)
                    cachedTradeIds[leaderId] = cachedIds
                    
                } else {
                }
                
                // 限制缓存大小，避免内存溢出（只保留最近1000条）
                if (cachedIds.size > 1000) {
                    // 保留最新的1000条（由于查询是按时间戳降序，保留前1000条即可）
                    val sortedTradeIds = allTrades.map { it.id }.take(1000).toSet()
                    cachedTradeIds[leaderId] = sortedTradeIds.toMutableSet()
                }
            }
        } catch (e: Exception) {
            logger.error("轮询Leader交易异常: leaderId=$leaderId, address=$leaderAddress", e)
        }
    }
}

