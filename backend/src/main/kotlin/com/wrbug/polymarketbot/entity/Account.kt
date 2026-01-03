package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 账户信息实体
 * 用于存储钱包账户信息（支持多账户）
 */
@Entity
@Table(name = "wallet_accounts")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "private_key", nullable = false, length = 500)
    val privateKey: String,  // 私钥（AES 加密存储）
    
    @Column(name = "wallet_address", unique = true, nullable = false, length = 42)
    val walletAddress: String,  // 钱包地址（从私钥推导）
    
    @Column(name = "proxy_address", nullable = false, length = 42)
    val proxyAddress: String,  // Polymarket 代理钱包地址（从合约获取，必须）
    
    @Column(name = "api_key", length = 500)
    val apiKey: String? = null,  // Polymarket API Key（可选，明文存储）
    
    @Column(name = "api_secret", length = 500)
    val apiSecret: String? = null,  // Polymarket API Secret（可选，AES 加密存储）
    
    @Column(name = "api_passphrase", length = 500)
    val apiPassphrase: String? = null,  // Polymarket API Passphrase（可选，AES 加密存储）
    
    @Column(name = "account_name", length = 100)
    val accountName: String? = null,
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,  // 是否默认账户
    
    @Column(name = "is_enabled", nullable = false)
    val isEnabled: Boolean = true,  // 是否启用（用于订单推送等功能的开关）
    
    @Column(name = "wallet_type", nullable = false, length = 20)
    val walletType: String = "magic",  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

