package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestTask
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * 回测任务Repository
 */
@Repository
interface BacktestTaskRepository : JpaRepository<BacktestTask, Long> {

    /**
     * 根据 Leader ID 查询回测任务
     */
    fun findByLeaderId(leaderId: Long): List<BacktestTask>

    /**
     * 根据状态查询回测任务
     */
    fun findByStatus(status: String): List<BacktestTask>

    /**
     * 根据 Leader ID 和状态查询回测任务
     */
    fun findByLeaderIdAndStatus(leaderId: Long, status: String): List<BacktestTask>

    /**
     * 根据 Leader ID、收益率排序查询
     */
    @Query("SELECT t FROM BacktestTask t WHERE t.leaderId = :leaderId AND t.status = :status ORDER BY t.profitRate DESC")
    fun findByLeaderIdAndStatusOrderByProfitRateDesc(leaderId: Long, status: String): List<BacktestTask>

    /**
     * 根据状态和创建时间倒序查询
     */
    @Query("SELECT t FROM BacktestTask t WHERE t.status = :status ORDER BY t.createdAt DESC")
    fun findByStatusOrderByCreatedAtDesc(status: String): List<BacktestTask>

    /**
     * 更新回测任务状态
     */
    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新回测任务状态和错误信息
     */
    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.errorMessage = :errorMessage, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatusAndError(id: Long, status: String, errorMessage: String?, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新回测任务进度
     */
    @Modifying
    @Query("UPDATE BacktestTask t SET t.progress = :progress, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateProgress(id: Long, progress: Int, updatedAt: Long = System.currentTimeMillis())
}

