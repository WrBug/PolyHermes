package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.CategoryValidator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Leader 管理服务
 */
@Service
class LeaderService(
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository
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
            val leader = Leader(
                leaderAddress = request.leaderAddress,
                leaderName = request.leaderName,
                category = request.category
            )
            
            val saved = leaderRepository.save(leader)
            logger.info("成功添加 Leader: ${saved.id}, ${saved.leaderAddress}")
            
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
            
            val updated = leader.copy(
                leaderName = request.leaderName ?: leader.leaderName,
                category = request.category ?: leader.category,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = leaderRepository.save(updated)
            logger.info("成功更新 Leader: ${saved.id}")
            
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
            logger.info("成功删除 Leader: $leaderId")
            
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
                toDto(leader, copyTradingCount)
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
            Result.success(toDto(leader, copyTradingCount))
        } catch (e: Exception) {
            logger.error("查询 Leader 详情失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     */
    private fun toDto(leader: Leader, copyTradingCount: Long = 0): LeaderDto {
        return LeaderDto(
            id = leader.id!!,
            leaderAddress = leader.leaderAddress,
            leaderName = leader.leaderName,
            category = leader.category,
            copyTradingCount = copyTradingCount,
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

