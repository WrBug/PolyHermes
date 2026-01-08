package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 市场信息实体
 * 用于缓存市场的基本信息（名称、slug等）
 */
@Entity
@Table(name = "markets", indexes = [
    Index(name = "idx_market_id", columnList = "market_id", unique = true)
])
data class Market(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "market_id", unique = true, nullable = false, length = 100)
    val marketId: String,  // 市场ID（condition ID）
    
    @Column(name = "title", nullable = false, length = 500)
    val title: String,  // 市场名称（question）
    
    @Column(name = "slug", length = 200)
    val slug: String? = null,  // 市场slug（用于显示）
    
    @Column(name = "event_slug", length = 200)
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    
    @Column(name = "category", length = 50)
    val category: String? = null,  // 市场分类
    
    @Column(name = "icon", length = 500)
    val icon: String? = null,  // 市场图标URL
    
    @Column(name = "image", length = 500)
    val image: String? = null,  // 市场图片URL
    
    @Column(name = "description", columnDefinition = "TEXT")
    val description: String? = null,  // 市场描述
    
    @Column(name = "active", nullable = false)
    val active: Boolean = true,  // 是否活跃
    
    @Column(name = "closed", nullable = false)
    val closed: Boolean = false,  // 是否已关闭
    
    @Column(name = "archived", nullable = false)
    val archived: Boolean = false,  // 是否已归档
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

