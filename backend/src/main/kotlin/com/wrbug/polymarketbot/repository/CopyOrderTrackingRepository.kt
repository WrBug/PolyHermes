package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 订单跟踪Repository
 */
@Repository
interface CopyOrderTrackingRepository : JpaRepository<CopyOrderTracking, Long> {
    
    /**
     * 根据跟单关系ID查询所有买入订单
     */
    fun findByCopyTradingId(copyTradingId: Long): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID、市场ID和方向查询未匹配的买入订单（FIFO顺序）
     * @deprecated 使用 findUnmatchedBuyOrdersByOutcomeIndex 替代
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.side = :side AND t.remainingQuantity > 0 ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrders(copyTradingId: Long, marketId: String, side: String): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID、市场ID和outcomeIndex查询未匹配的买入订单（FIFO顺序）
     * 支持多元市场（不限于YES/NO）
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrdersByOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID和状态查询订单
     */
    fun findByCopyTradingIdAndStatus(copyTradingId: Long, status: String): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID和市场ID查询订单
     */
    fun findByCopyTradingIdAndMarketId(copyTradingId: Long, marketId: String): List<CopyOrderTracking>
    
    /**
     * 根据Leader交易ID查询订单
     */
    fun findByLeaderBuyTradeId(leaderBuyTradeId: String): CopyOrderTracking?
    
    /**
     * 根据买入订单ID查询订单跟踪记录
     */
    fun findByBuyOrderId(buyOrderId: String): List<CopyOrderTracking>
    
    /**
     * 查询未发送通知的买入订单（用于轮询更新）
     */
    fun findByNotificationSentFalse(): List<CopyOrderTracking>
    
    /**
     * 查询指定时间之前创建的订单（用于检查30秒后未成交的订单）
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.createdAt <= :beforeTime")
    fun findByCreatedAtBefore(beforeTime: Long): List<CopyOrderTracking>

    /**
     * 查询指定时间之前创建且状态不为指定状态的订单
     */
    fun findByCreatedAtBeforeAndStatusNot(beforeTime: Long, status: String): List<CopyOrderTracking>

    /**
     * 查询指定跟单配置下的活跃仓位数量
     * 活跃仓位定义为 remainingQuantity > 0 的不同 (marketId, outcomeIndex) 组合
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(t.marketId, '_', COALESCE(t.outcomeIndex, -1))) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.remainingQuantity > 0")
    fun countActivePositions(copyTradingId: Long): Int

    /**
     * 检查指定市场是否存在活跃仓位
     */
    fun existsByCopyTradingIdAndMarketIdAndRemainingQuantityGreaterThan(copyTradingId: Long, marketId: String, remainingQuantity: BigDecimal): Boolean

    /**
     * 计算指定跟单配置和市场下的当前持仓总价值 (成本价计算)
     */
    @Query("SELECT SUM(t.remainingQuantity * t.price) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.remainingQuantity > 0")
    fun sumCurrentPositionValueByMarket(copyTradingId: Long, marketId: String): BigDecimal?
}

