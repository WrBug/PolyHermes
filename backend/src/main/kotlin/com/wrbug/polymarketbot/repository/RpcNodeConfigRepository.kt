package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.RpcNodeConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RpcNodeConfigRepository : JpaRepository<RpcNodeConfig, Long> {
    /**
     * 查询所有已启用的节点,按优先级排序(优先级数字越小越靠前)
     */
    fun findAllByEnabledTrueOrderByPriorityAsc(): List<RpcNodeConfig>
    
    /**
     * 查询指定 ID 的已启用节点
     */
    fun findByIdAndEnabledTrue(id: Long): RpcNodeConfig?
    
    /**
     * 查询所有节点,按优先级排序
     */
    fun findAllByOrderByPriorityAsc(): List<RpcNodeConfig>
}
