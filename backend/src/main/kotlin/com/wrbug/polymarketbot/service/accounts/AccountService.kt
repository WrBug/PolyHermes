package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.eq
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.util.CryptoUtils
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 账户管理服务
 */
@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService,
    private val apiKeyService: PolymarketApiKeyService,
    private val orderPushService: OrderPushService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val telegramNotificationService: TelegramNotificationService? = null,  // 可选，避免循环依赖
    private val relayClientService: RelayClientService,
    private val jsonUtils: JsonUtils
) {

    private val logger = LoggerFactory.getLogger(AccountService::class.java)
    
    // 协程作用域（用于异步发送通知）
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 市价单价格调整系数（在最优价基础上调整，确保更快成交）
    // 市价买单：bestAsk + BUY_PRICE_ADJUSTMENT（加价，确保能立即成交）
    // 市价卖单：bestBid - SELL_PRICE_ADJUSTMENT（减价，确保能立即成交）
    private val BUY_PRICE_ADJUSTMENT = BigDecimal("0.01")   // 买单价格调整系数（+0.01）
    private val SELL_PRICE_ADJUSTMENT = BigDecimal("0.02")  // 卖单价格调整系数（-0.02）

    /**
     * 通过私钥导入账户
     */
    @Transactional
    fun importAccount(request: AccountImportRequest): Result<AccountDto> {
        return try {
            // 1. 验证钱包地址格式
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }

            // 2. 检查地址是否已存在
            if (accountRepository.existsByWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("该钱包地址已存在"))
            }

            // 3. 验证私钥和地址的对应关系
            // 注意：前端已经验证了私钥和地址的对应关系，这里只做格式验证
            // 如果需要更严格的验证，可以使用以太坊库（如 web3j）进行验证
            if (!isValidPrivateKey(request.privateKey)) {
                return Result.failure(IllegalArgumentException("无效的私钥格式"))
            }

            // 4. 自动获取或创建 API Key（必须成功，否则导入失败）
            val apiKeyCreds = runBlocking {
                val result = apiKeyService.createOrDeriveApiKey(
                    privateKey = request.privateKey,
                    walletAddress = request.walletAddress,
                    chainId = 137L  // Polygon 主网
                )

                if (result.isSuccess) {
                    val creds = result.getOrNull()
                    if (creds != null) {
                        creds
                    } else {
                        logger.error("自动获取 API Key 返回空值")
                        throw IllegalStateException("自动获取 API Key 失败：返回值为空")
                    }
                } else {
                    val error = result.exceptionOrNull()
                    logger.error("自动获取 API Key 失败: ${error?.message}")
                    throw IllegalStateException("自动获取 API Key 失败: ${error?.message}。请确保私钥有效且账户已激活")
                }
            }

            // 5. 获取代理地址（必须成功，否则导入失败）
            // 根据用户选择的钱包类型计算代理地址
            val proxyAddress = runBlocking {
                val proxyResult = blockchainService.getProxyAddress(request.walletAddress, request.walletType)
                if (proxyResult.isSuccess) {
                    val address = proxyResult.getOrNull()
                    if (address != null) {
                        address
                    } else {
                        logger.error("获取代理地址返回空值")
                        throw IllegalStateException("获取代理地址失败：返回值为空")
                    }
                } else {
                    val error = proxyResult.exceptionOrNull()
                    logger.error("获取代理地址失败: ${error?.message}")
                    throw IllegalStateException("获取代理地址失败: ${error?.message}。请确保已配置 Ethereum RPC URL 且 RPC 节点可用")
                }
            }

            // 7. 加密敏感信息
            val encryptedPrivateKey = cryptoUtils.encrypt(request.privateKey)
            val encryptedApiSecret = apiKeyCreds.secret?.let { cryptoUtils.encrypt(it) }
            val encryptedApiPassphrase = apiKeyCreds.passphrase?.let { cryptoUtils.encrypt(it) }

            // 8. 生成账户名称（如果未提供，使用钱包地址后四位）
            val accountName = if (request.accountName.isNullOrBlank()) {
                val walletAddress = request.walletAddress.trim()
                // 取地址后四位（去掉 0x 前缀后取后四位）
                val addressWithoutPrefix = if (walletAddress.startsWith("0x") || walletAddress.startsWith("0X")) {
                    walletAddress.substring(2)
                } else {
                    walletAddress
                }
                if (addressWithoutPrefix.length >= 4) {
                    addressWithoutPrefix.substring(addressWithoutPrefix.length - 4).uppercase()
                } else {
                    addressWithoutPrefix.uppercase()
                }
            } else {
                request.accountName.trim()
            }

            // 9. 创建账户
            val account = Account(
                privateKey = encryptedPrivateKey,  // 存储加密后的私钥
                walletAddress = request.walletAddress,
                proxyAddress = proxyAddress,
                apiKey = apiKeyCreds.apiKey,
                apiSecret = encryptedApiSecret,  // 存储加密后的 API Secret
                apiPassphrase = encryptedApiPassphrase,  // 存储加密后的 API Passphrase
                accountName = accountName,
                isDefault = false,  // 不再支持默认账户
                isEnabled = request.isEnabled,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(account)

            // 刷新订单推送订阅（如果账户启用且有 API 凭证）
            orderPushService.refreshSubscriptions()

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("导入账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 更新账户信息
     */
    @Transactional
    fun updateAccount(request: AccountUpdateRequest): Result<AccountDto> {
        return try {
            val account = accountRepository.findById(request.accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 更新账户名称
            val updatedAccountName = request.accountName ?: account.accountName

            // 更新启用状态
            val updatedIsEnabled = request.isEnabled ?: account.isEnabled

            val updated = account.copy(
                accountName = updatedAccountName,
                isDefault = account.isDefault,  // 保持原值，不再支持修改
                isEnabled = updatedIsEnabled,
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(updated)

            // 刷新订单推送订阅（账户状态变更时）
            orderPushService.refreshSubscriptions()

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 刷新账户的代理地址
     * 使用最新的代理地址计算逻辑（支持 Magic 和 Safe 两种类型）
     */
    @Transactional
    fun refreshProxyAddress(accountId: Long): Result<AccountDto> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 重新获取代理地址
            val proxyAddress = runBlocking {
                val proxyResult = blockchainService.getProxyAddress(account.walletAddress)
                if (proxyResult.isSuccess) {
                    proxyResult.getOrNull()
                        ?: throw IllegalStateException("获取代理地址返回空值")
                } else {
                    val error = proxyResult.exceptionOrNull()
                    throw IllegalStateException("获取代理地址失败: ${error?.message}")
                }
            }

            // 更新账户
            val updated = account.copy(
                proxyAddress = proxyAddress,
                updatedAt = System.currentTimeMillis()
            )
            val saved = accountRepository.save(updated)

            logger.info("刷新代理地址成功: accountId=${accountId}, oldProxy=${account.proxyAddress}, newProxy=${proxyAddress}")
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("刷新代理地址失败: accountId=${accountId}", e)
            Result.failure(e)
        }
    }

    /**
     * 刷新所有账户的代理地址
     */
    @Transactional
    fun refreshAllProxyAddresses(): Result<List<AccountDto>> {
        return try {
            val accounts = accountRepository.findAll()
            val updatedAccounts = mutableListOf<AccountDto>()

            accounts.forEach { account ->
                try {
                    val proxyAddress = runBlocking {
                        val proxyResult = blockchainService.getProxyAddress(account.walletAddress)
                        if (proxyResult.isSuccess) {
                            proxyResult.getOrNull()
                        } else {
                            null
                        }
                    }

                    if (proxyAddress != null && proxyAddress != account.proxyAddress) {
                        val updated = account.copy(
                            proxyAddress = proxyAddress,
                            updatedAt = System.currentTimeMillis()
                        )
                        val saved = accountRepository.save(updated)
                        logger.info("刷新代理地址成功: accountId=${account.id}, oldProxy=${account.proxyAddress}, newProxy=${proxyAddress}")
                        updatedAccounts.add(toDto(saved))
                    }
                } catch (e: Exception) {
                    logger.warn("刷新账户 ${account.id} 代理地址失败: ${e.message}")
                }
            }

            logger.info("批量刷新代理地址完成: 更新了 ${updatedAccounts.size} 个账户")
            Result.success(updatedAccounts)
        } catch (e: Exception) {
            logger.error("批量刷新代理地址失败", e)
            Result.failure(e)
        }
    }

    /**
     * 删除账户
     */
    @Transactional
    fun deleteAccount(accountId: Long): Result<Unit> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 注意：不再检查活跃订单，允许用户删除有活跃订单的账户
            // 前端会显示确认提示框，由用户决定是否删除

            accountRepository.delete(account)

            // 刷新订单推送订阅（账户删除时）
            orderPushService.refreshSubscriptions()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户列表
     */
    fun getAccountList(): Result<AccountListResponse> {
        return try {
            val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
            val accountDtos = accounts.map { toDto(it) }

            Result.success(
                AccountListResponse(
                    list = accountDtos,
                    total = accountDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询账户列表失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户详情
     */
    fun getAccountDetail(accountId: Long?): Result<AccountDto> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }
            
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            Result.success(toDto(account))
        } catch (e: Exception) {
            logger.error("查询账户详情失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户余额
     * 通过链上 RPC 查询 USDC 余额，并通过 Subgraph API 查询持仓信息
     */
    fun getAccountBalance(accountId: Long?): Result<AccountBalanceResponse> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }
            
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 检查代理地址是否存在
            if (account.proxyAddress.isBlank()) {
                logger.error("账户 ${account.id} 的代理地址为空，无法查询余额")
                return Result.failure(IllegalStateException("账户代理地址不存在，无法查询余额。请重新导入账户以获取代理地址"))
            }

            // 查询 USDC 余额和持仓信息
            val balanceResult = runBlocking {
                try {
                    // 查询持仓信息（用于返回持仓列表）
                    // 使用代理地址查询持仓（Polymarket 使用代理地址存储持仓）
                    val positionsResult = blockchainService.getPositions(account.proxyAddress)
                    val positions = if (positionsResult.isSuccess) {
                        positionsResult.getOrNull()?.map { pos ->
                            PositionDto(
                                marketId = pos.conditionId ?: "",
                                side = pos.outcome ?: "",
                                quantity = pos.size?.toString() ?: "0",
                                avgPrice = pos.avgPrice?.toString() ?: "0",
                                currentValue = pos.currentValue?.toString() ?: "0",
                                pnl = pos.cashPnl?.toString()
                            )
                        } ?: emptyList()
                    } else {
                        logger.warn("持仓信息查询失败: ${positionsResult.exceptionOrNull()?.message}")
                        emptyList()
                    }

                    // 使用 /value 接口获取仓位总价值（而不是累加）
                    val positionBalanceResult = blockchainService.getTotalValue(account.proxyAddress)
                    val positionBalance = if (positionBalanceResult.isSuccess) {
                        positionBalanceResult.getOrNull() ?: "0"
                    } else {
                        logger.warn("仓位总价值查询失败: ${positionBalanceResult.exceptionOrNull()?.message}")
                        "0"
                    }

                    // 查询可用余额（通过 RPC 查询 USDC 余额）
                    // 必须使用代理地址查询
                    val availableBalanceResult = blockchainService.getUsdcBalance(
                        walletAddress = account.walletAddress,
                        proxyAddress = account.proxyAddress
                    )
                    val availableBalance = if (availableBalanceResult.isSuccess) {
                        availableBalanceResult.getOrNull() ?: throw Exception("USDC 余额查询返回空值")
                    } else {
                        // 如果 RPC 查询失败，返回错误（不返回 mock 数据）
                        val error = availableBalanceResult.exceptionOrNull()
                        logger.error("USDC 可用余额 RPC 查询失败: ${error?.message}")
                        throw Exception("USDC 可用余额查询失败: ${error?.message}。请确保已配置 Ethereum RPC URL")
                    }

                    // 计算总余额 = 可用余额 + 仓位余额
                    val totalBalance = availableBalance.toSafeBigDecimal().add(positionBalance.toSafeBigDecimal())

                    AccountBalanceResponse(
                        availableBalance = availableBalance,
                        positionBalance = positionBalance,
                        totalBalance = totalBalance.toPlainString(),
                        positions = positions
                    )
                } catch (e: Exception) {
                    logger.error("查询余额失败: ${e.message}", e)
                    throw e
                }
            }

            Result.success(balanceResult)
        } catch (e: Exception) {
            logger.error("查询账户余额失败", e)
            Result.failure(e)
        }
    }

    /**
     * 转换为 DTO
     * 包含交易统计数据（总订单数、总盈亏、活跃订单数、已完成订单数、持仓数量）
     */
    private fun toDto(account: Account): AccountDto {
        return runBlocking {
            val statistics = getAccountStatistics(account)
            AccountDto(
                id = account.id!!,
                walletAddress = account.walletAddress,
                proxyAddress = account.proxyAddress,
                accountName = account.accountName,
                isEnabled = account.isEnabled,
                apiKeyConfigured = account.apiKey != null,
                apiSecretConfigured = account.apiSecret != null,
                apiPassphraseConfigured = account.apiPassphrase != null,
                totalOrders = statistics.totalOrders,
                totalPnl = statistics.totalPnl,
                activeOrders = statistics.activeOrders,
                completedOrders = statistics.completedOrders,
                positionCount = statistics.positionCount
            )
        }
    }

    /**
     * 获取账户交易统计数据
     */
    private suspend fun getAccountStatistics(account: Account): AccountStatistics {
        return try {
            // 如果账户没有配置 API 凭证，无法查询统计数据
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return AccountStatistics(
                    totalOrders = null,
                    totalPnl = null,
                    activeOrders = null,
                    completedOrders = null,
                    positionCount = null
                )
            }

            // 解密 API 凭证
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)

            // 创建带认证的 API 客户端（需要钱包地址用于 POLY_ADDRESS 请求头）
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)

            // 1. 查询活跃订单数量（open/active 状态）
            val activeOrdersResult = try {
                var totalActiveOrders = 0L
                var nextCursor: String? = null

                // 分页查询所有活跃订单
                do {
                    val response = clobApi.getActiveOrders(
                        id = null,
                        market = null,
                        asset_id = null,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val ordersResponse = response.body()!!
                        totalActiveOrders += ordersResponse.data.size
                        nextCursor = ordersResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())

                Result.success(totalActiveOrders)
            } catch (e: Exception) {
                logger.warn("查询活跃订单失败: ${e.message}", e)
                Result.failure(e)
            }

            // 2. 查询已完成订单数
            // 注意：交易记录数不等于已完成订单数，因为一个订单可能产生多笔交易
            // 已完成订单应该是指已完全成交或已关闭的订单
            // 由于 Polymarket CLOB API 没有直接查询所有订单（包括已完成）的接口，
            // 我们通过查询交易记录来估算已完成订单数
            // 但更准确的方式是统计去重后的订单ID数量
            val completedOrdersResult = try {
                // 使用代理地址查询交易记录（作为 maker 的交易）
                var allTrades = mutableListOf<TradeResponse>()
                var nextCursor: String? = null

                // 分页查询所有交易（作为 maker）
                do {
                    val response = clobApi.getTrades(
                        maker_address = account.proxyAddress,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val tradesResponse = response.body()!!
                        allTrades.addAll(tradesResponse.data)
                        nextCursor = tradesResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())

                // 注意：Polymarket API 的 getTrades 接口只支持查询 maker_address，
                // 如果需要查询作为 taker 的交易，可能需要使用其他接口或查询方式
                // 目前只统计作为 maker 的交易记录

                // 由于 TradeResponse 没有 orderId 字段，我们无法直接去重订单
                // 这里使用交易记录数作为已完成订单数的近似值
                // 更准确的方式需要查询所有订单并统计状态为 "filled" 的订单
                val completedOrdersCount = allTrades.size.toLong()

                Result.success(completedOrdersCount)
            } catch (e: Exception) {
                logger.warn("查询交易记录失败: ${e.message}", e)
                Result.failure(e)
            }

            // 3. 查询仓位信息计算总盈亏（已实现盈亏）和持仓数量
            val positionsResult = try {
                val positions = blockchainService.getPositions(account.proxyAddress)
                if (positions.isSuccess) {
                    val positionList = positions.getOrNull() ?: emptyList()
                    // 汇总所有仓位的已实现盈亏
                    val totalRealizedPnl = positionList.sumOf { pos ->
                        pos.realizedPnl?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    }
                    // 统计持仓数量（所有非零持仓，包括正负仓位）
                    // size 可能为正数（做多）或负数（做空），都应该统计
                    val positionCount = positionList.count { pos ->
                        val size = pos.size?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        size != BigDecimal.ZERO  // 统计所有非零持仓
                    }
                    Result.success(Pair(totalRealizedPnl.toPlainString(), positionCount.toLong()))
                } else {
                    Result.failure(Exception("查询仓位信息失败"))
                }
            } catch (e: Exception) {
                logger.warn("查询仓位信息失败: ${e.message}", e)
                Result.failure(e)
            }

            val activeOrders = activeOrdersResult.getOrNull() ?: 0L
            val completedOrders = completedOrdersResult.getOrNull() ?: 0L
            // 总订单数 = 活跃订单数 + 已完成订单数
            val totalOrders = activeOrders + completedOrders
            val (totalPnl, positionCount) = positionsResult.getOrNull() ?: Pair(null, null)

            AccountStatistics(
                totalOrders = totalOrders,
                totalPnl = totalPnl,
                activeOrders = activeOrders,
                completedOrders = completedOrders,  // 已完成订单数 = 交易记录数（已成交的订单）
                positionCount = positionCount
            )
        } catch (e: Exception) {
            logger.warn("获取账户统计数据失败: ${e.message}", e)
            AccountStatistics(
                totalOrders = null,
                totalPnl = null,
                activeOrders = null,
                completedOrders = null,
                positionCount = null
            )
        }
    }

    /**
     * 账户统计数据
     */
    private data class AccountStatistics(
        val totalOrders: Long?,
        val totalPnl: String?,
        val activeOrders: Long?,
        val completedOrders: Long?,
        val positionCount: Long?
    )

    /**
     * 验证钱包地址格式
     */
    private fun isValidWalletAddress(address: String): Boolean {
        // 以太坊地址格式：0x 开头，42 位字符
        return address.startsWith("0x") && address.length == 42 && address.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }

    /**
     * 验证私钥格式
     */
    private fun isValidPrivateKey(privateKey: String): Boolean {
        // 私钥格式：64 位十六进制字符（可选 0x 前缀）
        val cleanKey = if (privateKey.startsWith("0x")) privateKey.substring(2) else privateKey
        return cleanKey.length == 64 && cleanKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    /**
     * 解密账户私钥
     */
    fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }
    
    /**
     * 解密账户 API Secret
     */
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }
    
    /**
     * 解密账户 API Passphrase
     */
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    /**
     * 查询所有账户的仓位列表
     * 返回所有账户的仓位信息，包括账户信息
     */
    suspend fun getAllPositions(): Result<PositionListResponse> {
        return try {
            val accounts = accountRepository.findAll()
            val currentPositions = mutableListOf<AccountPositionDto>()
            val historyPositions = mutableListOf<AccountPositionDto>()

            // 遍历所有账户，查询每个账户的仓位
            accounts.forEach { account ->
                if (account.proxyAddress.isNotBlank()) {
                    try {
                        // 查询所有仓位（不限制 sortBy，获取当前和历史仓位）
                        val positionsResult = blockchainService.getPositions(account.proxyAddress)
                        if (positionsResult.isSuccess) {
                            val positions = positionsResult.getOrNull() ?: emptyList()
                            // 遍历所有仓位，区分当前仓位和历史仓位
                            positions.forEach { pos ->
                                val currentValue = pos.currentValue?.toSafeBigDecimal() ?: BigDecimal.ZERO
                                val curPrice = pos.curPrice?.toSafeBigDecimal() ?: BigDecimal.ZERO

                                // 判断是否为当前仓位：currentValue != 0 且 curPrice != 0
                                // 使用 eq 方法判断值是否等于 0
                                val isCurrent = !currentValue.eq(BigDecimal.ZERO) && !curPrice.eq(BigDecimal.ZERO)

                                // 将 Double 转换为精确的 BigDecimal，保留完整精度
                                val sizeDecimal = pos.size?.let { 
                                    BigDecimal.valueOf(it)  // 使用 BigDecimal.valueOf 保留 Double 的完整精度
                                } ?: BigDecimal.ZERO
                                
                                // 显示用的数量（保留4位小数，用于显示）
                                val displayQuantity = sizeDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
                                // 原始数量（保留完整精度，用于100%出售）
                                val originalQuantity = sizeDecimal.toPlainString()

                                val positionDto = AccountPositionDto(
                                    accountId = account.id!!,
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    proxyAddress = account.proxyAddress,
                                    marketId = pos.conditionId ?: "",
                                    marketTitle = pos.title ?: "",
                                    marketSlug = pos.slug ?: "",
                                    marketIcon = pos.icon,  // 市场图标
                                    side = pos.outcome ?: "",
                                    outcomeIndex = pos.outcomeIndex,  // 添加 outcomeIndex
                                    quantity = displayQuantity,  // 显示用的数量
                                    originalQuantity = originalQuantity,  // 原始数量（完整精度）
                                    avgPrice = pos.avgPrice?.toString() ?: "0",
                                    currentPrice = pos.curPrice?.toString() ?: "0",
                                    currentValue = pos.currentValue?.toString() ?: "0",
                                    initialValue = pos.initialValue?.toString() ?: "0",
                                    pnl = pos.cashPnl?.toString() ?: "0",
                                    percentPnl = pos.percentPnl?.toString() ?: "0",
                                    realizedPnl = pos.realizedPnl?.toString(),
                                    percentRealizedPnl = pos.percentRealizedPnl?.toString(),
                                    redeemable = pos.redeemable ?: false,
                                    mergeable = pos.mergeable ?: false,
                                    endDate = pos.endDate,
                                    isCurrent = isCurrent  // 标识是当前仓位还是历史仓位
                                )

                                // 根据 isCurrent 分别添加到对应的列表
                                if (isCurrent) {
                                    currentPositions.add(positionDto)
                                } else {
                                    historyPositions.add(positionDto)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("查询账户 ${account.id} 仓位失败: ${e.message}", e)
                    }
                }
            }

            // 按照接口返回的顺序返回，不进行排序
            // 前端负责本地排序
            Result.success(
                PositionListResponse(
                    currentPositions = currentPositions,
                    historyPositions = historyPositions
                )
            )
        } catch (e: Exception) {
            logger.error("查询所有仓位失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 卖出仓位
     */
    suspend fun sellPosition(request: PositionSellRequest): Result<PositionSellResponse> {
        return try {
            // 1. 验证账户是否存在且已配置API凭证
            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return Result.failure(IllegalStateException("账户未配置API凭证，无法创建订单"))
            }

            // 2. 验证参数：percent 和 quantity 至少提供一个
            if (request.percent.isNullOrBlank() && request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("必须提供卖出数量(quantity)或卖出百分比(percent)"))
            }
            
            if (!request.percent.isNullOrBlank() && !request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("不能同时提供卖出数量(quantity)和卖出百分比(percent)"))
            }
            
            // 验证百分比值（如果提供了）
            val percentDecimal = if (!request.percent.isNullOrBlank()) {
                try {
                    val percent = request.percent!!.toSafeBigDecimal()
                    if (percent <= BigDecimal.ZERO || percent > BigDecimal.valueOf(100)) {
                        return Result.failure(IllegalArgumentException("卖出百分比必须在 0-100 之间"))
                    }
                    percent
                } catch (e: Exception) {
                    return Result.failure(IllegalArgumentException("卖出百分比格式不正确: ${e.message}"))
                }
            } else {
                null
            }

            // 3. 验证仓位是否存在并获取原始数量
            val positionsResult = getAllPositions()
            val (position, originalQuantity) = positionsResult.fold(
                onSuccess = { positionListResponse ->
                    val position = positionListResponse.currentPositions.find {
                        it.accountId == request.accountId &&
                                it.marketId == request.marketId &&
                                it.side == request.side
                    }

                    if (position == null) {
                        return Result.failure(IllegalArgumentException("仓位不存在"))
                    }

                    // 获取原始数量：如果有 originalQuantity 使用它，否则从 API 重新获取
                    val originalQty = if (position.originalQuantity != null) {
                        position.originalQuantity.toSafeBigDecimal()
                    } else {
                        // 如果没有 originalQuantity，从区块链服务重新获取原始数据
                        val blockchainPositionsResult = blockchainService.getPositions(account.proxyAddress)
                        if (blockchainPositionsResult.isSuccess) {
                            val blockchainPos = blockchainPositionsResult.getOrNull()?.find {
                                it.conditionId == request.marketId && it.outcome == request.side
                            }
                            blockchainPos?.size?.let { BigDecimal.valueOf(it) } ?: position.quantity.toSafeBigDecimal()
                        } else {
                            position.quantity.toSafeBigDecimal()
                        }
                    }
                    
                    Pair(position, originalQty)
                },
                onFailure = { e ->
                    return Result.failure(Exception("查询仓位失败: ${e.message}"))
                }
            ) ?: return Result.failure(IllegalArgumentException("仓位不存在"))

            // 4. 计算实际卖出数量
            val sellQuantity = if (percentDecimal != null) {
                // 使用百分比计算：原始数量 * 百分比 / 100
                originalQuantity.multiply(percentDecimal)
                    .divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.DOWN)
            } else {
                // 使用手动输入的数量
                request.quantity!!.toSafeBigDecimal()
            }

            // 5. 验证卖出数量
            if (sellQuantity <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("卖出数量必须大于0"))
            }

            if (sellQuantity > originalQuantity) {
                return Result.failure(IllegalArgumentException("卖出数量不能超过持仓数量"))
            }

            // 6. 获取 tokenId（从 conditionId 和 outcomeIndex 计算）
            // 需要先获取 tokenId，以便后续通过 CLOB API 获取三元及以上市场的价格
            // 优先使用 outcomeIndex，如果没有则返回错误（不再通过 side 字符串推断）
            val tokenIdResult = if (request.outcomeIndex != null) {
                blockchainService.getTokenId(request.marketId, request.outcomeIndex)
            } else {
                logger.warn("缺少 outcomeIndex 参数，无法计算 tokenId: marketId=${request.marketId}, side=${request.side}")
                Result.failure<String>(IllegalArgumentException("缺少 outcomeIndex 参数，无法计算 tokenId。请提供 outcomeIndex 参数"))
            }
            val tokenId = tokenIdResult.getOrNull()

            if (tokenId == null) {
                logger.warn("无法获取 tokenId，将使用 market 参数: conditionId=${request.marketId}, side=${request.side}, outcomeIndex=${request.outcomeIndex}, error=${tokenIdResult.exceptionOrNull()?.message}")
            }

            // 7. 验证 tokenId
            if (tokenId == null) {
                return Result.failure(IllegalStateException("无法获取 tokenId，无法创建订单。请确保已配置 Ethereum RPC URL 或提供 outcomeIndex 参数"))
            }

            // 8. 确定卖出价格
            // 市价单：从订单表获取最优价（通过 tokenId 获取对应 outcome 的订单表）
            // - 市价卖单：从订单表获取 bestBid（最高买入价），然后减去 SELL_PRICE_ADJUSTMENT
            // - 市价买单：从订单表获取 bestAsk（最低卖出价），然后加上 BUY_PRICE_ADJUSTMENT
            // 限价订单：使用用户输入的价格
            // 注意：使用 outcomeIndex 和 tokenId 支持多元市场（二元、三元及以上）
            // 如果无法获取订单表，将抛出异常
            val sellPrice = if (request.orderType == "MARKET") {
                try {
                    // 市价单：从订单表获取最优价（卖出订单，需要 bestBid）
                    // 通过 tokenId 获取对应 outcome 的订单表，支持多元市场
                    getOptimalPriceFromOrderbook(tokenId, isSellOrder = true)
                } catch (e: IllegalStateException) {
                    logger.error("无法获取订单表最优价: ${e.message}", e)
                    return Result.failure(IllegalStateException("无法获取订单表最优价: ${e.message}"))
                }
            } else {
                // 限价订单：使用用户输入的价格
                request.price ?: return Result.failure(IllegalArgumentException("限价订单必须提供价格"))
            }

            // 9. 验证价格
            val priceDecimal = sellPrice.toSafeBigDecimal()
            if (priceDecimal <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("价格必须大于0"))
            }

            // 10. 确定订单类型和过期时间
            // 根据官方文档：
            // - GTC (Good-Til-Cancelled): expiration 必须为 "0"
            // - GTD (Good-Til-Date): expiration 为具体的 Unix 时间戳（秒）
            // - FOK (Fill-Or-Kill): expiration 必须为 "0"
            // - FAK (Fill-And-Kill): expiration 必须为 "0"
            val orderType = when (request.orderType) {
                "MARKET" -> "FAK"  // Fill-And-Kill（与官方市价单一致，允许部分成交）
                "LIMIT" -> "GTC"   // Good-Til-Cancelled
                else -> "GTC"
            }

            // GTC 和 FOK 订单的 expiration 必须为 "0"
            // 只有 GTD 订单才需要设置具体的过期时间
            val expiration = "0"

            // 7. 解密私钥
            val decryptedPrivateKey = decryptPrivateKey(account)

            // 11. 创建并签名订单（使用计算后的卖出数量）
            val signedOrder = try {
                orderSigningService.createAndSignOrder(
                    privateKey = decryptedPrivateKey,
                    makerAddress = account.proxyAddress,  // 使用代理地址作为 maker
                    tokenId = tokenId,
                    side = "SELL",
                    price = sellPrice,
                    size = sellQuantity.toPlainString(),  // 使用计算后的卖出数量
                    signatureType = 2,  // Browser Wallet（与正确订单数据一致）
                    nonce = "0",
                    feeRateBps = "0",
                    expiration = expiration
                )
            } catch (e: Exception) {
                logger.error("创建并签名订单失败", e)
                return Result.failure(Exception("创建并签名订单失败: ${e.message}"))
            }

            // 12. 构建订单请求

            val newOrderRequest = com.wrbug.polymarketbot.api.NewOrderRequest(
                order = signedOrder,
                owner = account.apiKey!!,  // API Key
                orderType = orderType,
                deferExec = false
            )

            // 13. 解密 API 凭证并使用账户的API凭证创建订单
            val apiSecret = try {
                decryptApiSecret(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }
            val apiPassphrase = try {
                decryptApiPassphrase(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }

            val clobApi = retrofitFactory.createClobApi(
                account.apiKey!!,
                apiSecret,
                apiPassphrase,
                account.walletAddress
            )


            val orderResponse = clobApi.createOrder(newOrderRequest)

            if (orderResponse.isSuccessful && orderResponse.body() != null) {
                val response = orderResponse.body()!!
                if (response.success) {
                    val orderId = response.orderId ?: ""
                    
                    // 发送订单成功通知（异步，不阻塞）
                    notificationScope.launch {
                        try {
                            // 获取市场信息（标题和slug）
                            val marketInfo = withContext(Dispatchers.IO) {
                                try {
                                    val gammaApi = retrofitFactory.createGammaApi()
                                    val marketResponse = gammaApi.listMarkets(conditionIds = listOf(request.marketId))
                                    if (marketResponse.isSuccessful && marketResponse.body() != null) {
                                        marketResponse.body()!!.firstOrNull()
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    logger.warn("获取市场信息失败: ${e.message}", e)
                                    null
                                }
                            }
                            
                            val marketTitle = marketInfo?.question ?: request.marketId
                            val marketSlug = marketInfo?.slug
                            
                            // 获取当前语言设置（从 LocaleContextHolder）
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")  // 默认简体中文
                            }
                            
                            telegramNotificationService?.sendOrderSuccessNotification(
                                orderId = orderId,
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = request.side,
                                price = sellPrice,  // 直接传递卖出价格
                                size = sellQuantity.toPlainString(),  // 直接传递卖出数量
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                clobApi = clobApi,
                                apiKey = account.apiKey,
                                apiSecret = try { cryptoUtils.decrypt(account.apiSecret!!) } catch (e: Exception) { null },
                                apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase!!) } catch (e: Exception) { null },
                                walletAddressForApi = account.walletAddress,
                                locale = locale
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单成功通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.success(
                        PositionSellResponse(
                            orderId = orderId,
                            marketId = request.marketId,
                            side = request.side,
                            orderType = request.orderType,
                            quantity = sellQuantity.toPlainString(),  // 使用计算后的卖出数量
                            price = if (request.orderType == "LIMIT") sellPrice else null,
                            status = "pending",  // 订单状态需要从响应中获取
                            createdAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val errorMsg = response.errorMsg ?: "未知错误"
                    val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, errorMsg=$errorMsg"
                    logger.error(fullErrorMsg)
                    
                    // 发送订单失败通知（异步，不阻塞）
                    notificationScope.launch {
                        try {
                            // 获取市场信息（标题和slug）
                            val marketInfo = withContext(Dispatchers.IO) {
                                try {
                                    val gammaApi = retrofitFactory.createGammaApi()
                                    val marketResponse = gammaApi.listMarkets(conditionIds = listOf(request.marketId))
                                    if (marketResponse.isSuccessful && marketResponse.body() != null) {
                                        marketResponse.body()!!.firstOrNull()
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    logger.warn("获取市场信息失败: ${e.message}", e)
                                    null
                                }
                            }
                            
                            val marketTitle = marketInfo?.question ?: request.marketId
                            val marketSlug = marketInfo?.slug
                            
                            // 获取当前语言设置（从 LocaleContextHolder）
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")  // 默认简体中文
                            }
                            
                            telegramNotificationService?.sendOrderFailureNotification(
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = request.side,
                                outcome = null,  // 失败时可能没有 outcome
                                price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                                size = sellQuantity.toString(),
                                errorMessage = errorMsg,  // 只传递后端返回的 msg
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                locale = locale
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单失败通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.failure(Exception(fullErrorMsg))
                }
            } else {
                val errorBody = try {
                    orderResponse.errorBody()?.string()
                } catch (e: Exception) {
                    null
                }
                val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, code=${orderResponse.code()}, message=${orderResponse.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                logger.error(fullErrorMsg)
                
                // 发送订单失败通知（异步，不阻塞）
                notificationScope.launch {
                    try {
                        // 获取市场信息（标题和slug）
                        val marketInfo = withContext(Dispatchers.IO) {
                            try {
                                val gammaApi = retrofitFactory.createGammaApi()
                                val marketResponse = gammaApi.listMarkets(conditionIds = listOf(request.marketId))
                                if (marketResponse.isSuccessful && marketResponse.body() != null) {
                                    marketResponse.body()!!.firstOrNull()
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                logger.warn("获取市场信息失败: ${e.message}", e)
                                null
                            }
                        }
                        
                        val marketTitle = marketInfo?.question ?: request.marketId
                        val marketSlug = marketInfo?.slug
                        
                        // 获取当前语言设置（从 LocaleContextHolder）
                        val locale = try {
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()
                        } catch (e: Exception) {
                            java.util.Locale("zh", "CN")  // 默认简体中文
                        }
                        
                        // 只传递后端返回的 msg，不传递完整堆栈
                        val errorMsg = orderResponse.body()?.errorMsg ?: "创建订单失败"
                        
                        telegramNotificationService?.sendOrderFailureNotification(
                            marketTitle = marketTitle,
                            marketId = request.marketId,
                            marketSlug = marketSlug,
                            side = request.side,
                            outcome = null,  // 失败时可能没有 outcome
                            price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                            size = sellQuantity.toString(),
                            errorMessage = errorMsg,  // 只传递后端返回的 msg
                            accountName = account.accountName,
                            walletAddress = account.walletAddress,
                            locale = locale
                        )
                    } catch (e: Exception) {
                        logger.warn("发送订单失败通知失败: ${e.message}", e)
                    }
                }
                
                Result.failure(Exception(fullErrorMsg))
            }
        } catch (e: Exception) {
            val fullErrorMsg = "卖出仓位异常: accountId=${request.accountId}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, error=${e.message}"
            logger.error(fullErrorMsg, e)
            Result.failure(Exception(fullErrorMsg))
        }
    }

    /**
     * 从订单表获取最优价（用于市价单）
     * 支持多元市场（二元、三元及以上）
     * 委托给 com.wrbug.polymarketbot.service.common.PolymarketClobService.getOptimalPrice 方法
     *
     * @param tokenId token ID（通过 marketId 和 outcomeIndex 计算得出）
     * @param isSellOrder 是否为卖出订单（true: 卖单，需要 bestBid；false: 买单，需要 bestAsk）
     * @return 最优价格（已应用调整系数）
     * @throws IllegalStateException 如果无法获取订单表或订单表为空
     */
    private suspend fun getOptimalPriceFromOrderbook(tokenId: String, isSellOrder: Boolean): String {
        return clobService.getOptimalPrice(
            tokenId = tokenId,
            isSellOrder = isSellOrder,
            buyPriceAdjustment = BUY_PRICE_ADJUSTMENT,
            sellPriceAdjustment = SELL_PRICE_ADJUSTMENT
        )
    }

    /**
     * 获取市场价格
     * 使用 Gamma API 获取价格信息，因为 Gamma API 支持 condition_ids 参数
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引（可选）：0, 1, 2...，用于确定需要查询哪个 outcome 的价格。如果提供了 outcomeIndex 且 > 0，会转换价格（1 - 第一个outcome的价格）
     */
    suspend fun getMarketPrice(marketId: String, outcomeIndex: Int? = null): Result<MarketPriceResponse> {
        return try {
            // 使用 Gamma API 获取市场信息（支持 condition_ids 参数）
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(marketId))

            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val market = markets.firstOrNull()

                if (market != null) {
                    // 从 Gamma API 响应中提取价格信息（这些价格通常是针对第一个 outcome，index = 0）
                    var bestBid = market.bestBid?.toString()
                    var bestAsk = market.bestAsk?.toString()
                    var lastPrice = market.lastTradePrice?.toString()

                    // 如果目标 outcome 不是第一个（index != 0），需要转换价格
                    // 对于二元市场：第二个 outcome 的价格 = 1 - 第一个 outcome 的价格
                    if (outcomeIndex != null && outcomeIndex > 0) {
                        val outcomes = jsonUtils.parseStringArray(market.outcomes)
                        // 只对二元市场进行价格转换
                        if (outcomes.size == 2) {
                            // 保存原始第一个 outcome 的价格
                            val firstOutcomeBestBid = bestBid
                            val firstOutcomeBestAsk = bestAsk
                            
                            // 转换价格：第二个 outcome 的 bestBid = 1 - 第一个 outcome 的 bestAsk
                            // 第二个 outcome 的 bestAsk = 1 - 第一个 outcome 的 bestBid
                            bestBid = firstOutcomeBestAsk?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            bestAsk = firstOutcomeBestBid?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            
                            // 转换最后成交价：第二个 outcome 的 lastPrice = 1 - 第一个 outcome 的 lastPrice
                            lastPrice = lastPrice?.let {
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                        }
                    }

                    // 计算中间价 = (bestBid + bestAsk) / 2
                    val midpoint = if (bestBid != null && bestAsk != null) {
                        val bid = bestBid.toSafeBigDecimal()
                        val ask = bestAsk.toSafeBigDecimal()
                        bid.add(ask).divide(BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP).toString()
                    } else {
                        null
                    }

                    // 优先使用 lastPrice（最近成交价），如果没有则使用 bestBid，最后使用 midpoint
                    val currentPrice = lastPrice ?: bestBid ?: midpoint ?: "0"

                    Result.success(
                        MarketPriceResponse(
                            marketId = marketId,
                            currentPrice = currentPrice
                        )
                    )
                } else {
                    Result.failure(Exception("未找到市场信息: $marketId"))
                }
            } else {
                Result.failure(Exception("获取市场价格失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取市场价格异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 获取可赎回仓位统计
     */
    suspend fun getRedeemablePositionsSummary(accountId: Long? = null): Result<RedeemablePositionsSummary> {
        return try {
            val positionsResult = getAllPositions()
            positionsResult.fold(
                onSuccess = { positionListResponse ->
                    // 筛选可赎回的仓位
                    val redeemablePositions = positionListResponse.currentPositions.filter { it.redeemable }

                    // 如果指定了账户ID，进一步筛选
                    val filteredPositions = if (accountId != null) {
                        redeemablePositions.filter { it.accountId == accountId }
                    } else {
                        redeemablePositions
                    }

                    // 计算总价值（赎回是1:1，所以价值等于数量）
                    val totalValue = filteredPositions.fold(BigDecimal.ZERO) { sum, pos ->
                        sum.add(pos.quantity.toSafeBigDecimal())
                    }

                    // 转换为可赎回仓位信息列表
                    val redeemableInfoList = filteredPositions.map { pos ->
                        com.wrbug.polymarketbot.dto.RedeemablePositionInfo(
                            accountId = pos.accountId,
                            accountName = pos.accountName,
                            marketId = pos.marketId,
                            marketTitle = pos.marketTitle,
                            side = pos.side,
                            outcomeIndex = pos.outcomeIndex ?: 0,
                            quantity = pos.quantity,
                            value = pos.quantity  // 赎回价值等于数量（1:1）
                        )
                    }

                    Result.success(
                        com.wrbug.polymarketbot.dto.RedeemablePositionsSummary(
                            totalCount = redeemableInfoList.size,
                            totalValue = totalValue.toPlainString(),
                            positions = redeemableInfoList
                        )
                    )
                },
                onFailure = { e ->
                    Result.failure(Exception("查询仓位失败: ${e.message}"))
                }
            )
        } catch (e: Exception) {
            logger.error("获取可赎回仓位统计失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 赎回仓位
     * 支持多账户、多仓位赎回（自动按账户和市场分组）
     */
    suspend fun redeemPositions(request: PositionRedeemRequest): Result<PositionRedeemResponse> {
        return try {
            // 检查 Builder API Key 是否已配置
            if (!relayClientService.isBuilderApiKeyConfigured()) {
                return Result.failure(
                    IllegalStateException("Builder API Key 未配置，无法执行 Gasless 交易。请前往系统设置页面配置 Builder API Key。")
                )
            }
            
            if (request.positions.isEmpty()) {
                return Result.failure(IllegalArgumentException("赎回仓位列表不能为空"))
            }

            // 1. 验证仓位是否存在且可赎回
            val positionsResult = getAllPositions()
            val allPositions = positionsResult.getOrElse {
                return Result.failure(Exception("查询仓位失败: ${it.message}"))
            }

            // 2. 按账户分组
            val positionsByAccount = request.positions.groupBy { it.accountId }

            // 3. 验证所有账户是否存在
            val accounts = mutableMapOf<Long, Account>()
            for (accountId in positionsByAccount.keys) {
                val account = accountRepository.findById(accountId).orElse(null)
                    ?: return Result.failure(IllegalArgumentException("账户不存在: $accountId"))
                accounts[accountId] = account
            }

            // 4. 验证并收集要赎回的仓位信息（按账户分组）
            val accountRedeemData = mutableMapOf<Long, MutableList<Pair<AccountPositionDto, BigInteger>>>()
            val accountRedeemedInfo =
                mutableMapOf<Long, MutableList<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>>()

            for ((accountId, requestItems) in positionsByAccount) {
                val accountPositions = mutableListOf<Pair<AccountPositionDto, BigInteger>>()
                val accountInfo = mutableListOf<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>()

                for (requestItem in requestItems) {
                    val position = allPositions.currentPositions.find {
                        it.accountId == accountId &&
                                it.marketId == requestItem.marketId &&
                                it.outcomeIndex == requestItem.outcomeIndex
                    }

                    if (position == null) {
                        return Result.failure(IllegalArgumentException("仓位不存在: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }

                    if (!position.redeemable) {
                        return Result.failure(IllegalStateException("仓位不可赎回: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }

                    // 计算 indexSet = 2^outcomeIndex
                    val indexSet = BigInteger.TWO.pow(requestItem.outcomeIndex)
                    accountPositions.add(Pair(position, indexSet))

                    accountInfo.add(
                        com.wrbug.polymarketbot.dto.RedeemedPositionInfo(
                            marketId = position.marketId,
                            side = position.side,
                            outcomeIndex = requestItem.outcomeIndex,
                            quantity = position.quantity,
                            value = position.quantity  // 赎回价值等于数量（1:1）
                        )
                    )
                }

                accountRedeemData[accountId] = accountPositions
                accountRedeemedInfo[accountId] = accountInfo
            }

            // 5. 对每个账户执行赎回
            val accountTransactions = mutableListOf<com.wrbug.polymarketbot.dto.AccountRedeemTransaction>()
            var totalRedeemedValue = BigDecimal.ZERO

            for ((accountId, positions) in accountRedeemData) {
                val account = accounts[accountId]!!
                val redeemedInfo = accountRedeemedInfo[accountId]!!

                // 按市场分组（同一市场的仓位可以批量赎回）
                val positionsByMarket = positions.groupBy { it.first.marketId }

                // 对每个市场执行赎回
                var lastTxHash: String? = null
                for ((marketId, marketPositions) in positionsByMarket) {
                    val indexSets = marketPositions.map { it.second }

                    // 解密私钥
                    val decryptedPrivateKey = decryptPrivateKey(account)

                    // 调用区块链服务赎回仓位
                    val redeemResult = blockchainService.redeemPositions(
                        privateKey = decryptedPrivateKey,
                        proxyAddress = account.proxyAddress,
                        conditionId = marketId,
                        indexSets = indexSets
                    )

                    redeemResult.fold(
                        onSuccess = { txHash ->
                            lastTxHash = txHash
                        },
                        onFailure = { e ->
                            logger.error("账户 $accountId 市场 $marketId 赎回失败: ${e.message}", e)
                            return Result.failure(Exception("赎回失败: 账户 $accountId 市场 $marketId - ${e.message}"))
                        }
                    )
                }

                // 计算该账户的赎回总价值
                val accountTotalValue = redeemedInfo.fold(BigDecimal.ZERO) { sum, info ->
                    sum.add(info.value.toSafeBigDecimal())
                }
                totalRedeemedValue = totalRedeemedValue.add(accountTotalValue)

                // 添加到交易列表
                accountTransactions.add(
                    com.wrbug.polymarketbot.dto.AccountRedeemTransaction(
                        accountId = accountId,
                        accountName = account.accountName,
                        transactionHash = lastTxHash ?: "",
                        positions = redeemedInfo
                    )
                )
            }

            // 6. 发送赎回推送通知（异步，不阻塞）
            notificationScope.launch {
                try {
                    // 获取当前语言设置
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (e: Exception) {
                        java.util.Locale("zh", "CN")  // 默认简体中文
                    }
                    
                    // 为每个账户发送推送
                    for (transaction in accountTransactions) {
                        val account = accounts[transaction.accountId]
                        if (account != null) {
                            telegramNotificationService?.sendRedeemNotification(
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                transactionHash = transaction.transactionHash,
                                totalRedeemedValue = transaction.positions.fold(BigDecimal.ZERO) { sum, info ->
                                    sum.add(info.value.toSafeBigDecimal())
                                }.toPlainString(),
                                positions = transaction.positions,
                                locale = locale
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("发送赎回推送通知失败: ${e.message}", e)
                }
            }
            
            // 7. 返回结果
            Result.success(
                com.wrbug.polymarketbot.dto.PositionRedeemResponse(
                    transactions = accountTransactions,
                    totalRedeemedValue = totalRedeemedValue.toPlainString(),
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            logger.error("赎回仓位异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 检查账户是否有活跃订单
     * 使用账户的 API Key 查询该账户的活跃订单
     */
    private suspend fun hasActiveOrders(account: Account): Boolean {
        return try {
            // 如果账户没有配置 API 凭证，无法查询活跃订单，允许删除
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return false
            }

            // 解密 API 凭证
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)

            // 创建带认证的 API 客户端（需要钱包地址用于 POLY_ADDRESS 请求头）
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)

            // 查询活跃订单（只查询第一条，用于判断是否有订单）
            // 使用 next_cursor 参数进行分页，这里只查询第一页
            val response = clobApi.getActiveOrders(
                id = null,
                market = null,
                asset_id = null,
                next_cursor = null  // null 表示从第一页开始
            )

            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                val hasOrders = ordersResponse.data.isNotEmpty()
                hasOrders
            } else {
                // 如果查询失败（可能是认证失败或网络问题），记录警告但允许删除
                // 因为无法确定是否有活跃订单，不应该阻止删除操作
                logger.warn("查询活跃订单失败: ${response.code()} ${response.message()}，允许删除账户")
                false
            }
        } catch (e: Exception) {
            // 如果查询异常（网络问题、API 错误等），记录警告但允许删除
            // 因为无法确定是否有活跃订单，不应该阻止删除操作
            logger.warn("检查活跃订单异常: ${e.message}，允许删除账户", e)
            false
        }
    }
}


