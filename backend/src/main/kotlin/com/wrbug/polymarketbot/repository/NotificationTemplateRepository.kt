package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NotificationTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, Long> {
    fun findByTemplateType(templateType: String): NotificationTemplate?
    fun existsByTemplateType(templateType: String): Boolean
}
