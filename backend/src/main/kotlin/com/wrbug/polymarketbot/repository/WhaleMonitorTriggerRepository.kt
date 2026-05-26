package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.WhaleMonitorTrigger
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WhaleMonitorTriggerRepository : JpaRepository<WhaleMonitorTrigger, Long> {
    fun findAllByStrategyIdOrderByCreatedAtDesc(strategyId: Long, pageable: Pageable): Page<WhaleMonitorTrigger>
    fun findAllByStrategyIdAndStatusOrderByCreatedAtDesc(strategyId: Long, status: String, pageable: Pageable): Page<WhaleMonitorTrigger>
    fun findAllByStrategyIdAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, startTs: Long, endTs: Long, pageable: Pageable): Page<WhaleMonitorTrigger>
    fun findAllByStrategyIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(strategyId: Long, status: String, startTs: Long, endTs: Long, pageable: Pageable): Page<WhaleMonitorTrigger>
    fun countByStrategyId(strategyId: Long): Long
    fun countByStrategyIdAndStatus(strategyId: Long, status: String): Long
    fun countByStrategyIdAndCreatedAtBetween(strategyId: Long, startTs: Long, endTs: Long): Long
    fun countByStrategyIdAndStatusAndCreatedAtBetween(strategyId: Long, status: String, startTs: Long, endTs: Long): Long
}
