package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 跟单监听服务（主服务）
 * 管理所有Leader的交易监听
 * 使用双重监听机制：
 * 1. Activity WebSocket - 低延迟（< 100ms），优先使用
 * 2. OnChain WebSocket - 高可靠性（~2-3s），作为兜底
 * 同时监听跟单账户的卖出/赎回事件（通过链上 WebSocket）
 */
@Service
class CopyTradingMonitorService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val activityWsService: PolymarketActivityWsService,
    private val onChainWsService: OnChainWsService,
    private val accountOnChainMonitorService: AccountOnChainMonitorService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingMonitorService::class.java)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * 系统启动时初始化监听
     */
    @PostConstruct
    fun init() {
        scope.launch {
            try {
                startMonitoring()
            } catch (e: Exception) {
                logger.error("启动跟单监听失败", e)
            }
        }
    }
    
    /**
     * 系统关闭时清理资源
     */
    @PreDestroy
    fun destroy() {
        scope.cancel()
        // 停止所有监听
        activityWsService.stop()
        onChainWsService.stop()
        accountOnChainMonitorService.stop()
    }
    
    /**
     * 启动监听
     * 启动双重监听机制：
     * 1. Activity WebSocket - 低延迟（< 100ms），优先使用
     * 2. OnChain WebSocket - 高可靠性（~2-3s），作为兜底
     * 同时启动跟单账户的链上 WebSocket 监听（用于检测卖出/赎回事件）
     */
    suspend fun startMonitoring() {
        // 1. 获取所有启用的跟单关系
        val enabledCopyTradings = copyTradingRepository.findByEnabledTrue()
        
        if (enabledCopyTradings.isEmpty()) {
            return
        }
        
        // 2. 获取所有需要监听的Leader（去重）
        val leaderIds = enabledCopyTradings.map { it.leaderId }.distinct()
        val leaders = leaderIds.mapNotNull { leaderId ->
            leaderRepository.findById(leaderId).orElse(null)
        }
        
        // 3. 获取所有需要监听的跟单账户（去重）
        val accountIds = enabledCopyTradings.map { it.accountId }.distinct()
        val accounts = accountIds.mapNotNull { accountId ->
            accountRepository.findById(accountId).orElse(null)
        }
        
        // 4. 启动 Activity WebSocket 监听（优先，低延迟）
        activityWsService.start(leaders)
        
        // 5. 启动链上 WebSocket 监听（兜底，高可靠性）
        onChainWsService.start(leaders)
        
        // 6. 启动跟单账户的链上 WebSocket 监听（用于检测卖出/赎回事件）
        accountOnChainMonitorService.start(accounts)
    }
    
    /**
     * 添加Leader监听（当创建新的跟单关系时调用）
     * 如果 Leader 已经在监听列表中，不重复添加
     */
    suspend fun addLeaderMonitoring(leaderId: Long) {
        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: return
        
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        if (copyTradings.isEmpty()) {
            return
        }
        
        // 同时添加到两种监听
        activityWsService.addLeader(leader)
        onChainWsService.addLeader(leader)
    }
    
    /**
     * 移除Leader监听（当删除跟单关系或禁用时调用）
     * 检查该 Leader 是否还有其他启用的跟单配置
     */
    suspend fun removeLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        // 如果还有启用的跟单配置，不移除监听
        if (copyTradings.isNotEmpty()) {
            return
        }
        
        // 没有启用的跟单配置了，同时从两种监听移除
        activityWsService.removeLeader(leaderId)
        onChainWsService.removeLeader(leaderId)
    }
    
    /**
     * 更新Leader监听（当跟单配置状态改变时调用）
     * 根据当前状态决定添加或移除监听
     */
    suspend fun updateLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: return
        
        if (copyTradings.isNotEmpty()) {
            // 有启用的跟单配置，确保在监听列表中
            activityWsService.addLeader(leader)
            onChainWsService.addLeader(leader)
            
            // 更新账户监听（添加该配置关联的账户）
            val accountIds = copyTradings.map { it.accountId }.distinct()
            accountIds.forEach { accountId ->
                val account = accountRepository.findById(accountId).orElse(null)
                if (account != null) {
                    accountOnChainMonitorService.addAccount(account)
                }
            }
        } else {
            // 没有启用的跟单配置，同时从两种监听移除
            activityWsService.removeLeader(leaderId)
            onChainWsService.removeLeader(leaderId)
        }
    }
    
    /**
     * 更新账户监听（当跟单配置状态改变时调用）
     * 根据当前状态决定添加或移除账户监听
     */
    suspend fun updateAccountMonitoring(accountId: Long) {
        val copyTradings = copyTradingRepository.findByAccountId(accountId)
            .filter { it.enabled }
        val account = accountRepository.findById(accountId).orElse(null)
            ?: return
        
        if (copyTradings.isNotEmpty()) {
            // 有启用的跟单配置，确保账户在监听列表中
            accountOnChainMonitorService.addAccount(account)
        } else {
            // 没有启用的跟单配置，移除账户监听
            accountOnChainMonitorService.removeAccount(accountId)
        }
    }
    
    /**
     * 重新启动监听（当跟单关系状态改变时调用）
     * 注意：这个方法会停止所有监听并重新启动，建议使用 updateLeaderMonitoring 进行增量更新
     */
    suspend fun restartMonitoring() {
        // 停止所有监听
        activityWsService.stop()
        onChainWsService.stop()
        delay(1000)  // 等待1秒
        startMonitoring()
    }
}

