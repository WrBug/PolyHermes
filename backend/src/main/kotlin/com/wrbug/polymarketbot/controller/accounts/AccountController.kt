package com.wrbug.polymarketbot.controller.accounts

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * 账户管理控制器
 */
@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountService: AccountService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(AccountController::class.java)

    /**
     * 通过私钥导入账户
     */
    @PostMapping("/import")
    fun importAccount(@RequestBody request: AccountImportRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            // 参数验证
            if (request.privateKey.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_PRIVATE_KEY_EMPTY, messageSource = messageSource))
            }
            if (request.walletAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_WALLET_ADDRESS_EMPTY, messageSource = messageSource))
            }

            val result = accountService.importAccount(request)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("导入账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_IMPORT_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("导入账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_IMPORT_FAILED, e.message, messageSource))
        }
    }

    /**
     * 更新账户信息
     */
    @PostMapping("/update")
    fun updateAccount(@RequestBody request: AccountUpdateRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            val result = accountService.updateAccount(request)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("更新账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_UPDATE_FAILED, e.message, messageSource))
        }
    }

    /**
     * 刷新账户的代理地址
     * 使用最新的代理地址计算逻辑（支持 Magic 和 Safe 两种类型）
     */
    @PostMapping("/refresh-proxy")
    fun refreshProxyAddress(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            if (request.accountId == null || request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }

            val result = accountService.refreshProxyAddress(request.accountId)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("刷新代理地址失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("刷新代理地址异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 批量刷新所有账户的代理地址
     */
    @PostMapping("/refresh-all-proxies")
    fun refreshAllProxyAddresses(): ResponseEntity<ApiResponse<List<AccountDto>>> {
        return try {
            val result = accountService.refreshAllProxyAddresses()
            result.fold(
                onSuccess = { accounts ->
                    ResponseEntity.ok(ApiResponse.success(accounts))
                },
                onFailure = { e ->
                    logger.error("批量刷新代理地址失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("批量刷新代理地址异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    /**
     * 删除账户
     */
    @PostMapping("/delete")
    fun deleteAccount(@RequestBody request: AccountDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = accountService.deleteAccount(request.accountId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DELETE_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询账户列表
     */
    @PostMapping("/list")
    fun getAccountList(): ResponseEntity<ApiResponse<AccountListResponse>> {
        return try {
            val result = accountService.getAccountList()
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询账户列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询账户详情
     */
    @PostMapping("/detail")
    fun getAccountDetail(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            val result = accountService.getAccountDetail(request.accountId)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("查询账户详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_DETAIL_FETCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询账户余额
     */
    @PostMapping("/balance")
    fun getAccountBalance(@RequestBody request: AccountBalanceRequest): ResponseEntity<ApiResponse<AccountBalanceResponse>> {
        return try {
            val result = accountService.getAccountBalance(request.accountId)
            result.fold(
                onSuccess = { balance ->
                    ResponseEntity.ok(ApiResponse.success(balance))
                },
                onFailure = { e ->
                    logger.error("查询账户余额失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_BALANCE_FETCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户余额异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_BALANCE_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询所有账户的仓位列表
     */
    @PostMapping("/positions/list")
    fun getAllPositions(): ResponseEntity<ApiResponse<PositionListResponse>> {
        return try {
            val result = runBlocking { accountService.getAllPositions() }
            result.fold(
                onSuccess = { positionListResponse ->
                    ResponseEntity.ok(ApiResponse.success(positionListResponse))
                },
                onFailure = { e ->
                    logger.error("查询仓位列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_POSITIONS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询仓位列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_POSITIONS_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 卖出仓位
     */
    @PostMapping("/positions/sell")
    fun sellPosition(@RequestBody request: PositionSellRequest): ResponseEntity<ApiResponse<PositionSellResponse>> {
        return try {
            // 参数验证
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            if (request.marketId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
            }
            // side 可以是任意结果名称（如 "YES", "NO", "Pakistan" 等），不再限制为 YES/NO
            if (request.side.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_SIDE_EMPTY, messageSource = messageSource))
            }
            if (request.orderType !in listOf("MARKET", "LIMIT")) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ORDER_TYPE_MUST_BE_MARKET_OR_LIMIT, messageSource = messageSource))
            }
            // 如果传了 percent，不需要校验 quantity；如果没传 percent，必须提供 quantity
            if (request.percent.isNullOrBlank() && request.quantity.isNullOrBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_QUANTITY_EMPTY, messageSource = messageSource))
            }
            // 如果传了 percent，验证百分比值必须在 0-100 之间（支持小数）
            if (!request.percent.isNullOrBlank()) {
                try {
                    val percent = request.percent.toSafeBigDecimal()
                    if (percent <= BigDecimal.ZERO || percent > BigDecimal.valueOf(100)) {
                        return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "卖出百分比必须在 0-100 之间", messageSource))
                    }
                } catch (e: Exception) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "卖出百分比格式不正确: ${e.message}", messageSource))
                }
            }
            if (request.orderType == "LIMIT" && (request.price == null || request.price.isBlank())) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_PRICE_EMPTY, messageSource = messageSource))
            }

            val result = runBlocking { accountService.sellPosition(request) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("创建卖出订单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_ORDER_CREATE_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建卖出订单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_ORDER_CREATE_FAILED, e.message, messageSource))
        }
    }

    /**
     * 获取可赎回仓位统计
     */
    @PostMapping("/positions/redeemable-summary")
    fun getRedeemablePositionsSummary(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<RedeemablePositionsSummary>> {
        return try {
            val result = runBlocking { accountService.getRedeemablePositionsSummary(request.accountId) }
            result.fold(
                onSuccess = { summary ->
                    ResponseEntity.ok(ApiResponse.success(summary))
                },
                onFailure = { e ->
                    logger.error("获取可赎回仓位统计失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ERROR,
                                "获取可赎回仓位统计失败: ${e.message}",
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取可赎回仓位统计异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取可赎回仓位统计失败: ${e.message}", messageSource))
        }
    }

    /**
     * 赎回仓位
     */
    @PostMapping("/positions/redeem")
    fun redeemPositions(@RequestBody request: PositionRedeemRequest): ResponseEntity<ApiResponse<PositionRedeemResponse>> {
        return try {
            // 参数验证
            if (request.positions.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_REDEEM_POSITIONS_EMPTY, messageSource = messageSource))
            }

            // 验证每个仓位项
            for (item in request.positions) {
                if (item.accountId <= 0) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
                }
                if (item.marketId.isBlank()) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
                }
                if (item.outcomeIndex < 0) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INDEX_SETS_INVALID, messageSource = messageSource))
                }
            }

            val result = runBlocking { accountService.redeemPositions(request) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("赎回仓位失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> {
                            // 检查是否是 Builder API Key 未配置的错误
                            if (e.message?.contains("Builder API Key 未配置") == true) {
                                ResponseEntity.ok(
                                    ApiResponse.error(
                                        ErrorCode.BUILDER_API_KEY_NOT_CONFIGURED,
                                        "${e.message} 请前往系统设置页面配置：/system-settings",
                                        messageSource
                                    )
                                )
                            } else {
                                ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                            }
                        }

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_REDEEM_POSITIONS_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("赎回仓位异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_REDEEM_POSITIONS_FAILED, e.message, messageSource))
        }
    }

}

