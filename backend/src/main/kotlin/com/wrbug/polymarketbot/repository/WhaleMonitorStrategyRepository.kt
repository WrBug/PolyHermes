package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.WhaleMonitorStrategy
import org.springframework.data.jpa.repository.JpaRepository

interface WhaleMonitorStrategyRepository : JpaRepository<WhaleMonitorStrategy, Long> {
    fun findAllByAccountId(accountId: Long): List<WhaleMonitorStrategy>
    fun findAllByEnabledTrue(): List<WhaleMonitorStrategy>
    fun findByAccountIdAndEnabled(accountId: Long, enabled: Boolean): List<WhaleMonitorStrategy>
}
