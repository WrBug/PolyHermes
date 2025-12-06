package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.PositionPushMessageType
import com.wrbug.polymarketbot.dto.getPositionKey
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 仓位推送服务
 * 轮询仓位接口，比较差异并推送增量更新
 */
@Service
class PositionPushService(
    private val accountService: AccountService,
    private val positionCheckService: PositionCheckService
) {
    
    private val logger = LoggerFactory.getLogger(PositionPushService::class.java)
    
    @Value("\${position.push.polling-interval:3000}")
    private var pollingInterval: Long = 3000  // 轮询间隔（毫秒），默认3秒
    
    // 存储客户端会话和对应的推送回调
    private val clientCallbacks = ConcurrentHashMap<String, (PositionPushMessage) -> Unit>()
    
    // 存储上一次的仓位数据快照（用于比较差异）
    private var lastCurrentPositions: Map<String, AccountPositionDto> = emptyMap()
    private var lastHistoryPositions: Map<String, AccountPositionDto> = emptyMap()
    
    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null
    
    // 同步锁，确保轮询任务的启动和停止是线程安全的
    private val lock = Any()
    
    /**
     * 初始化服务（后端启动时直接启动轮询）
     */
    @PostConstruct
    fun init() {
        logger.info("PositionPushService 初始化，启动仓位轮询任务")
        startPolling()
    }
    
    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        scope.cancel()
    }
    
    /**
     * 订阅仓位推送（新接口）
     */
    fun subscribe(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        registerSession(sessionId, callback)
    }
    
    /**
     * 取消订阅仓位推送（新接口）
     */
    fun unsubscribe(sessionId: String) {
        unregisterSession(sessionId)
    }
    
    /**
     * 注册客户端会话（兼容旧接口）
     * 轮询任务已在后端启动时启动，这里只需要注册回调
     */
    fun registerSession(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        logger.info("注册仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks[sessionId] = callback
            // 轮询任务已在后端启动时启动，不需要在这里启动
        }
    }
    
    /**
     * 注销客户端会话（兼容旧接口）
     * 轮询任务持续运行，不因客户端断开而停止
     */
    fun unregisterSession(sessionId: String) {
        logger.info("注销仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks.remove(sessionId)
            // 轮询任务持续运行，不停止
        }
    }
    
    /**
     * 发送全量数据给指定客户端
     */
    suspend fun sendFullData(sessionId: String) {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    val message = PositionPushMessage(
                        type = PositionPushMessageType.FULL,
                        timestamp = System.currentTimeMillis(),
                        currentPositions = positions.currentPositions,
                        historyPositions = positions.historyPositions
                    )
                    
                    // 更新快照
                    lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
                    lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
                    
                    // 发送给指定客户端
                    clientCallbacks[sessionId]?.invoke(message)
                }
            } else {
                logger.warn("获取仓位数据失败，无法发送全量数据: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("发送全量仓位数据失败: $sessionId, ${e.message}", e)
        }
    }
    
    /**
     * 启动轮询任务
     */
    private fun startPolling() {
        synchronized(lock) {
            // 如果已经有轮询任务在运行，先取消
            pollingJob?.cancel()
            
            // 启动新的轮询任务
            pollingJob = scope.launch {
                while (isActive) {
                    try {
                        pollAndPush()
                    } catch (e: Exception) {
                        logger.error("轮询仓位数据失败: ${e.message}", e)
                    }
                    delay(pollingInterval)
                }
            }
        }
    }
    
    /**
     * 停止轮询任务
     */
    private fun stopPolling() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
    }
    
    /**
     * 轮询仓位数据并推送全量数据
     * 根据文档要求：每次轮训完成后向订阅者发送全量数据
     */
    private suspend fun pollAndPush() {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    // 更新快照
                    lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
                    lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
                    
                    // 向所有订阅者发送全量数据
                    if (clientCallbacks.isNotEmpty()) {
                        val message = PositionPushMessage(
                            type = PositionPushMessageType.FULL,
                            timestamp = System.currentTimeMillis(),
                            currentPositions = positions.currentPositions,
                            historyPositions = positions.historyPositions
                        )
                        
                        // 推送给所有连接的客户端
                        clientCallbacks.values.forEach { callback ->
                            try {
                                callback(message)
                            } catch (e: Exception) {
                                logger.error("推送全量数据失败: ${e.message}", e)
                            }
                        }
                    }
                    
                    // 仓位检查逻辑（复用仓位轮询）
                    // 处理待赎回仓位和未卖出订单
                    positionCheckService.checkPositions(positions.currentPositions)
                }
            } else {
                logger.warn("获取仓位数据失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("轮询仓位数据异常: ${e.message}", e)
        }
    }
    
    /**
     * 计算增量更新
     * 返回 null 表示没有变化
     */
    private fun calculateIncremental(
        newCurrentPositions: List<AccountPositionDto>,
        newHistoryPositions: List<AccountPositionDto>
    ): IncrementalUpdate? {
        val newCurrentMap = newCurrentPositions.associateBy { it.getPositionKey() }
        val newHistoryMap = newHistoryPositions.associateBy { it.getPositionKey() }
        
        // 找出新增或更新的当前仓位
        val updatedCurrentPositions = mutableListOf<AccountPositionDto>()
        newCurrentMap.forEach { (key, newPos) ->
            val oldPos = lastCurrentPositions[key]
            if (oldPos == null || hasChanged(oldPos, newPos)) {
                updatedCurrentPositions.add(newPos)
            }
        }
        
        // 找出新增或更新的历史仓位
        val updatedHistoryPositions = mutableListOf<AccountPositionDto>()
        newHistoryMap.forEach { (key, newPos) ->
            val oldPos = lastHistoryPositions[key]
            if (oldPos == null || hasChanged(oldPos, newPos)) {
                updatedHistoryPositions.add(newPos)
            }
        }
        
        // 找出已删除的仓位（从当前仓位变为历史仓位，或完全删除）
        val removedKeys = mutableListOf<String>()
        
        // 检查上次的当前仓位是否还在当前仓位列表中
        lastCurrentPositions.forEach { (key, _) ->
            if (!newCurrentMap.containsKey(key)) {
                // 如果不在当前仓位中，检查是否移到了历史仓位
                if (!newHistoryMap.containsKey(key)) {
                    // 完全删除
                    removedKeys.add(key)
                } else {
                    // 从当前仓位移到历史仓位，需要更新历史仓位
                    newHistoryMap[key]?.let { updatedHistoryPositions.add(it) }
                }
            }
        }
        
        // 检查上次的历史仓位是否还在历史仓位列表中
        lastHistoryPositions.forEach { (key, _) ->
            if (!newHistoryMap.containsKey(key)) {
                // 如果不在历史仓位中，检查是否移到了当前仓位
                if (!newCurrentMap.containsKey(key)) {
                    // 完全删除
                    removedKeys.add(key)
                } else {
                    // 从历史仓位移到当前仓位，需要更新当前仓位
                    newCurrentMap[key]?.let { updatedCurrentPositions.add(it) }
                }
            }
        }
        
        // 如果没有变化，返回 null
        if (updatedCurrentPositions.isEmpty() && updatedHistoryPositions.isEmpty() && removedKeys.isEmpty()) {
            return null
        }
        
        return IncrementalUpdate(
            currentPositions = updatedCurrentPositions,
            historyPositions = updatedHistoryPositions,
            removedKeys = removedKeys
        )
    }
    
    /**
     * 检查仓位是否有变化
     * 比较关键字段：数量、价格、价值、盈亏等
     */
    private fun hasChanged(old: AccountPositionDto, new: AccountPositionDto): Boolean {
        return old.quantity != new.quantity ||
                old.avgPrice != new.avgPrice ||
                old.currentPrice != new.currentPrice ||
                old.currentValue != new.currentValue ||
                old.pnl != new.pnl ||
                old.percentPnl != new.percentPnl ||
                old.realizedPnl != new.realizedPnl ||
                old.percentRealizedPnl != new.percentRealizedPnl ||
                old.redeemable != new.redeemable ||
                old.mergeable != new.mergeable ||
                old.isCurrent != new.isCurrent
    }
    
    /**
     * 增量更新数据
     */
    private data class IncrementalUpdate(
        val currentPositions: List<AccountPositionDto>,
        val historyPositions: List<AccountPositionDto>,
        val removedKeys: List<String>
    )
}

