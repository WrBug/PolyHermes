package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NbaMarket
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NbaMarketRepository : JpaRepository<NbaMarket, Long> {
    fun findByConditionId(conditionId: String): NbaMarket?
    fun findByPolymarketMarketId(polymarketMarketId: String): NbaMarket?
    fun findByActiveAndClosed(active: Boolean, closed: Boolean): List<NbaMarket>
    fun findByCategory(category: String): List<NbaMarket>
    fun findByActive(active: Boolean): List<NbaMarket>
}

