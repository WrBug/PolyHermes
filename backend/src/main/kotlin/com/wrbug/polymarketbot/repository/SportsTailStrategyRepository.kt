package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SportsTailStrategy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
interface SportsTailStrategyRepository : JpaRepository<SportsTailStrategy, Long> {

    /** 查询所有策略 */
    fun findAllByOrderByCreatedAtDesc(): List<SportsTailStrategy>

    /** 按账户查询 */
    fun findAllByAccountIdOrderByCreatedAtDesc(accountId: Long): List<SportsTailStrategy>

    /** 按账户和 conditionId 查询 */
    fun findByAccountIdAndConditionId(accountId: Long, conditionId: String): SportsTailStrategy?

    /** 按条件查询（用于列表筛选） */
    fun findAllByAccountId(accountId: Long): List<SportsTailStrategy>

    /** 查询未成交的策略 */
    fun findAllByFilledFalse(): List<SportsTailStrategy>

    /** 查询已成交但未卖出的策略 */
    fun findAllByFilledTrueAndSoldFalse(): List<SportsTailStrategy>

    /** 按 conditionId 查询未完成的策略（未成交或已成交未卖出） */
    @Query("SELECT s FROM SportsTailStrategy s WHERE s.conditionId = :conditionId AND (s.filled = false OR s.sold = false)")
    fun findActiveByConditionId(@Param("conditionId") conditionId: String): List<SportsTailStrategy>

    /** 按 conditionId 查询未成交的策略 */
    @Query("SELECT s FROM SportsTailStrategy s WHERE s.conditionId = :conditionId AND s.filled = false")
    fun findPendingByConditionId(@Param("conditionId") conditionId: String): List<SportsTailStrategy>

    /** 按 conditionId 查询已成交但未卖出的策略（用于止盈止损监控） */
    @Query("SELECT s FROM SportsTailStrategy s WHERE s.conditionId = :conditionId AND s.filled = true AND s.sold = false")
    fun findFilledByConditionId(@Param("conditionId") conditionId: String): List<SportsTailStrategy>

    /** 按 conditionId 查询已成交但未卖出且有止盈止损的策略 */
    @Query("SELECT s FROM SportsTailStrategy s WHERE s.conditionId = :conditionId AND s.filled = true AND s.sold = false AND (s.takeProfitPrice IS NOT NULL OR s.stopLossPrice IS NOT NULL)")
    fun findFilledWithStopByConditionId(@Param("conditionId") conditionId: String): List<SportsTailStrategy>

    /** 按账户统计总盈亏 */
    @Query("SELECT SUM(s.realizedPnl) FROM SportsTailStrategy s WHERE s.accountId = :accountId AND s.sold = true")
    fun sumRealizedPnlByAccountId(@Param("accountId") accountId: Long): BigDecimal?

    /** 按策略统计总盈亏 */
    @Query("SELECT SUM(t.realizedPnl) FROM SportsTailStrategyTrigger t WHERE t.strategyId = :strategyId AND t.sellStatus = 'SUCCESS'")
    fun sumRealizedPnlByStrategyId(@Param("strategyId") strategyId: Long): BigDecimal?
}
