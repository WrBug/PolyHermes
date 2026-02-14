package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.springframework.data.jpa.repository.JpaRepository

interface CryptoTailStrategyRepository : JpaRepository<CryptoTailStrategy, Long> {

    fun findAllByAccountId(accountId: Long): List<CryptoTailStrategy>
    fun findAllByEnabledTrue(): List<CryptoTailStrategy>
    fun findByAccountIdAndEnabled(accountId: Long, enabled: Boolean): List<CryptoTailStrategy>
}
