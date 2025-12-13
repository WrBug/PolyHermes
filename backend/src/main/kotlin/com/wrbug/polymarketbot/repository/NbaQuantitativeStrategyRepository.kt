package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NbaQuantitativeStrategy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NbaQuantitativeStrategyRepository : JpaRepository<NbaQuantitativeStrategy, Long> {
    fun findByAccountId(accountId: Long): List<NbaQuantitativeStrategy>
    fun findByAccountIdAndEnabled(accountId: Long, enabled: Boolean): List<NbaQuantitativeStrategy>
    fun findByEnabled(enabled: Boolean): List<NbaQuantitativeStrategy>
    fun findByStrategyName(strategyName: String): NbaQuantitativeStrategy?
}

