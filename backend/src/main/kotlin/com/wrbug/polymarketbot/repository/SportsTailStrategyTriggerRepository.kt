package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SportsTailStrategyTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SportsTailStrategyTriggerRepository : JpaRepository<SportsTailStrategyTrigger, Long> {

    /** 按策略ID查询（分页） */
    fun findAllByStrategyIdOrderByTriggeredAtDesc(strategyId: Long, pageable: Pageable): Page<SportsTailStrategyTrigger>

    /** 按账户ID查询（分页） */
    fun findAllByAccountIdOrderByTriggeredAtDesc(accountId: Long, pageable: Pageable): Page<SportsTailStrategyTrigger>

    /** 按账户ID和时间范围查询（分页） */
    fun findAllByAccountIdAndTriggeredAtBetweenOrderByTriggeredAtDesc(
        accountId: Long,
        startTime: Long,
        endTime: Long,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 全局查询（分页） */
    fun findAllByOrderByTriggeredAtDesc(pageable: Pageable): Page<SportsTailStrategyTrigger>

    /** 全局按时间范围查询（分页） */
    fun findAllByTriggeredAtBetweenOrderByTriggeredAtDesc(
        startTime: Long,
        endTime: Long,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 按账户ID和买入状态查询 */
    fun findAllByAccountIdAndBuyStatusOrderByTriggeredAtDesc(
        accountId: Long,
        buyStatus: String,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 按账户ID和时间范围和买入状态查询 */
    fun findAllByAccountIdAndBuyStatusAndTriggeredAtBetweenOrderByTriggeredAtDesc(
        accountId: Long,
        buyStatus: String,
        startTime: Long,
        endTime: Long,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 统计总数 */
    fun countByAccountId(accountId: Long): Long

    fun countByAccountIdAndBuyStatus(accountId: Long, buyStatus: String): Long

    fun countByAccountIdAndTriggeredAtBetween(accountId: Long, startTime: Long, endTime: Long): Long

    fun countByAccountIdAndBuyStatusAndTriggeredAtBetween(
        accountId: Long,
        buyStatus: String,
        startTime: Long,
        endTime: Long
    ): Long

    fun countByTriggeredAtBetween(startTime: Long, endTime: Long): Long

    fun countByBuyStatusAndTriggeredAtBetween(buyStatus: String, startTime: Long, endTime: Long): Long

    /** 全局按买入状态查询（分页） */
    fun findAllByBuyStatusOrderByTriggeredAtDesc(
        buyStatus: String,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 全局按买入状态和时间范围查询（分页） */
    fun findAllByBuyStatusAndTriggeredAtBetweenOrderByTriggeredAtDesc(
        buyStatus: String,
        startTime: Long,
        endTime: Long,
        pageable: Pageable
    ): Page<SportsTailStrategyTrigger>

    /** 全局统计 */
    fun countByBuyStatus(buyStatus: String): Long

    /** 查询某策略最近一条买入成功的触发记录（用于卖出时更新） */
    fun findFirstByStrategyIdAndBuyStatusOrderByTriggeredAtDesc(
        strategyId: Long,
        buyStatus: String
    ): SportsTailStrategyTrigger?
}
