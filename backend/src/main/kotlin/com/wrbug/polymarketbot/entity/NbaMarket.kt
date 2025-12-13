package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * NBA 市场实体（Polymarket 市场信息）
 */
@Entity
@Table(name = "nba_markets")
data class NbaMarket(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "polymarket_market_id", unique = true, nullable = false, length = 100)
    val polymarketMarketId: String,
    
    @Column(name = "condition_id", unique = true, nullable = false, length = 100)
    val conditionId: String,
    
    @Column(name = "market_slug", length = 255)
    val marketSlug: String? = null,
    
    @Column(name = "market_question", columnDefinition = "TEXT")
    val marketQuestion: String? = null,
    
    @Column(name = "market_description", columnDefinition = "TEXT")
    val marketDescription: String? = null,
    
    @Column(name = "category", length = 50)
    val category: String = "sports",
    
    @Column(name = "active")
    val active: Boolean = true,
    
    @Column(name = "closed")
    val closed: Boolean = false,
    
    @Column(name = "archived")
    val archived: Boolean = false,
    
    @Column(name = "volume", length = 50)
    val volume: String? = null,
    
    @Column(name = "liquidity", length = 50)
    val liquidity: String? = null,
    
    @Column(name = "outcomes", columnDefinition = "TEXT")
    val outcomes: String? = null,
    
    @Column(name = "end_date", length = 50)
    val endDate: String? = null,
    
    @Column(name = "start_date", length = 50)
    val startDate: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

