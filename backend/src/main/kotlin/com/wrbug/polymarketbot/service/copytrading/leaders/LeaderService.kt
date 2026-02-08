package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.util.CategoryValidator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlinx.coroutines.runBlocking

/**
 * Leader 管理服务
 */
@Service
class LeaderService(
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val backtestTaskRepository: BacktestTaskRepository,
    private val blockchainService: BlockchainService
) {

    private val logger = LoggerFactory.getLogger(LeaderService::class.java)
    
    /**
     * 添加被跟单者
     */
    @Transactional
    fun addLeader(request: LeaderAddRequest): Result<LeaderDto> {
        return try {
            // 1. 验证地址格式
            if (!isValidWalletAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }
            
            // 2. 验证分类
            if (request.category != null) {
                CategoryValidator.validate(request.category)
            }
            
            // 3. 检查是否已存在
            if (leaderRepository.existsByLeaderAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("该 Leader 地址已存在"))
            }
            
            // 4. 验证 Leader 地址不能与自己的地址相同
            if (accountRepository.existsByWalletAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("Leader 地址不能与自己的账户地址相同"))
            }
            
            // 5. 创建 Leader
            // 如果 website 为空，自动设置为 polymarket profile 页
            val website = if (request.website.isNullOrBlank()) {
                "https://polymarket.com/profile/${request.leaderAddress}"
            } else {
                request.website
            }
            
            val leader = Leader(
                leaderAddress = request.leaderAddress,
                leaderName = request.leaderName?.takeIf { it.isNotBlank() },
                category = request.category,
                remark = request.remark?.takeIf { it.isNotBlank() },
                website = website
            )
            
            val saved = leaderRepository.save(leader)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("添加 Leader 失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新被跟单者
     */
    @Transactional
    fun updateLeader(request: LeaderUpdateRequest): Result<LeaderDto> {
        return try {
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))
            
            // 验证分类
            if (request.category != null) {
                CategoryValidator.validate(request.category)
            }
            
            // 处理更新逻辑：如果请求中的字段为 null 或空字符串，都设置为 null
            // 如果 website 为空，自动设置为 polymarket profile 页
            val website = if (request.website.isNullOrBlank()) {
                "https://polymarket.com/profile/${leader.leaderAddress}"
            } else {
                request.website
            }
            
            val updated = leader.copy(
                leaderName = request.leaderName?.takeIf { it.isNotBlank() },
                category = request.category,
                remark = request.remark?.takeIf { it.isNotBlank() },
                website = website,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = leaderRepository.save(updated)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新 Leader 失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除被跟单者
     */
    @Transactional
    fun deleteLeader(leaderId: Long): Result<Unit> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))
            
            // 检查是否有跟单关系
            val copyTradingCount = copyTradingRepository.countByLeaderId(leaderId)
            if (copyTradingCount > 0) {
                return Result.failure(IllegalStateException("该 Leader 还有 $copyTradingCount 个跟单关系，请先删除跟单关系"))
            }
            
            leaderRepository.delete(leader)
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除 Leader 失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询 Leader 列表
     */
    fun getLeaderList(request: LeaderListRequest): Result<LeaderListResponse> {
        return try {
            // 验证分类
            if (request.category != null) {
                CategoryValidator.validate(request.category)
            }
            
            val leaders = if (request.category != null) {
                leaderRepository.findByCategory(request.category)
            } else {
                leaderRepository.findAllByOrderByCreatedAtAsc()
            }
            
            val leaderDtos = leaders.map { leader ->
                val copyTradingCount = copyTradingRepository.countByLeaderId(leader.id!!)
                val backtestCount = backtestTaskRepository.findByLeaderId(leader.id).size.toLong()
                toDto(leader, copyTradingCount, backtestCount)
            }
            
            Result.success(
                LeaderListResponse(
                    list = leaderDtos,
                    total = leaderDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 列表失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询 Leader 详情
     */
    fun getLeaderDetail(leaderId: Long): Result<LeaderDto> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            val copyTradingCount = copyTradingRepository.countByLeaderId(leaderId)
            val backtestCount = backtestTaskRepository.findByLeaderId(leaderId).size.toLong()
            Result.success(toDto(leader, copyTradingCount, backtestCount))
        } catch (e: Exception) {
            logger.error("查询 Leader 详情失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询 Leader 余额
     * 使用代理地址查询 USDC 余额和持仓信息
     */
    fun getLeaderBalance(leaderId: Long): Result<LeaderBalanceResponse> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            // Leader 的 leaderAddress 就是代理地址
            val walletAddress = leader.leaderAddress

            // 使用通用方法查询余额
            val balanceResult = runBlocking {
                blockchainService.getWalletBalance(walletAddress)
            }

            balanceResult.map { walletBalance: WalletBalanceResponse ->
                LeaderBalanceResponse(
                    leaderId = leader.id!!,
                    leaderAddress = leader.leaderAddress,
                    leaderName = leader.leaderName,
                    availableBalance = walletBalance.availableBalance,
                    positionBalance = walletBalance.positionBalance,
                    totalBalance = walletBalance.totalBalance,
                    positions = walletBalance.positions
                )
            }
        } catch (e: Exception) {
            logger.error("查询 Leader 余额失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     */
    private fun toDto(leader: Leader, copyTradingCount: Long = 0, backtestCount: Long = 0): LeaderDto {
        return LeaderDto(
            id = leader.id!!,
            leaderAddress = leader.leaderAddress,
            leaderName = leader.leaderName,
            category = leader.category,
            remark = leader.remark,
            website = leader.website,
            copyTradingCount = copyTradingCount,
            backtestCount = backtestCount,
            createdAt = leader.createdAt,
            updatedAt = leader.updatedAt
        )
    }
    
    /**
     * 验证钱包地址格式
     * 必须是 0x 开头的 42 位十六进制字符串
     */
    private fun isValidWalletAddress(address: String): Boolean {
        return address.matches(Regex("^0x[a-fA-F0-9]{40}$"))
    }
}

