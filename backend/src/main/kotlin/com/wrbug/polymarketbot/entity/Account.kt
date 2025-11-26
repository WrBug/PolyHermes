package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 账户信息实体
 * 用于存储跟单者的账户信息（支持多账户）
 */
@Entity
@Table(name = "copy_trading_accounts")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "private_key", nullable = false, length = 500)
    val privateKey: String,  // 私钥（明文存储）
    
    @Column(name = "wallet_address", unique = true, nullable = false, length = 42)
    val walletAddress: String,  // 钱包地址（从私钥推导）
    
    @Column(name = "proxy_address", nullable = false, length = 42)
    val proxyAddress: String,  // Polymarket 代理钱包地址（从合约获取，必须）
    
    @Column(name = "api_key", length = 500)
    val apiKey: String? = null,  // Polymarket API Key（可选，明文存储）
    
    @Column(name = "api_secret", length = 500)
    val apiSecret: String? = null,  // Polymarket API Secret（可选，明文存储）
    
    @Column(name = "api_passphrase", length = 500)
    val apiPassphrase: String? = null,  // Polymarket API Passphrase（可选，明文存储）
    
    @Column(name = "account_name", length = 100)
    val accountName: String? = null,
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,  // 是否默认账户
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

