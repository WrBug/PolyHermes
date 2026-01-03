package com.wrbug.polymarketbot.service.common

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 频率限制服务（使用内存缓存）
 */
@Service
class RateLimitService {

    private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

    // 重置密码限速配置
    @Value("\${rate-limit.reset-password.max-attempts:3}")
    private var resetPasswordMaxAttempts: Int = 3

    @Value("\${rate-limit.reset-password.window-seconds:60}")
    private var resetPasswordWindowSeconds: Long = 60

    // 登录限速配置
    @Value("\${rate-limit.login.max-attempts:5}")
    private var loginMaxAttempts: Int = 5

    @Value("\${rate-limit.login.window-seconds:300}")
    private var loginWindowSeconds: Long = 300  // 5分钟

    @Value("\${rate-limit.login.lockout-seconds:900}")
    private var loginLockoutSeconds: Long = 900  // 15分钟

    // 全局尝试记录列表（时间戳），所有请求共享
    private val resetPasswordAttempts = AtomicReference<MutableList<Long>>(mutableListOf())

    // 登录失败尝试记录（IP -> 时间戳列表）
    private val loginFailedAttempts = ConcurrentHashMap<String, MutableList<Long>>()

    // 登录锁定记录（IP -> 锁定结束时间）
    private val loginLockouts = ConcurrentHashMap<String, Long>()

    /**
     * 检查重置密码频率限制（全局限制，不按IP）
     * @return Result，如果超过限制则返回失败
     */
    fun checkResetPasswordRateLimit(): Result<Unit> {
        val now = System.currentTimeMillis()
        val windowStart = now - (resetPasswordWindowSeconds * 1000)

        // 获取当前尝试记录列表
        val attempts = resetPasswordAttempts.get()

        // 清理过期记录（超过时间窗口的记录）
        val validAttempts = attempts.filter { it >= windowStart }.toMutableList()

        // 检查是否超过限制
        if (validAttempts.size >= resetPasswordMaxAttempts) {
            logger.warn("重置密码频率限制触发: attempts=${validAttempts.size}/$resetPasswordMaxAttempts")
            return Result.failure(IllegalStateException("频率限制：1分钟内最多尝试${resetPasswordMaxAttempts}次，请稍后再试"))
        }

        // 记录本次尝试
        validAttempts.add(now)
        resetPasswordAttempts.set(validAttempts)

        return Result.success(Unit)
    }

    /**
     * 检查登录频率限制（按IP限制）
     * @param ipAddress 客户端IP地址
     * @return Result，如果被锁定或超过限制则返回失败
     */
    fun checkLoginRateLimit(ipAddress: String): Result<Unit> {
        val now = System.currentTimeMillis()

        // 检查是否被锁定
        val lockoutEndTime = loginLockouts[ipAddress]
        if (lockoutEndTime != null) {
            if (now < lockoutEndTime) {
                val remainingSeconds = (lockoutEndTime - now) / 1000
                logger.warn("登录锁定中: ip=$ipAddress, remainingSeconds=$remainingSeconds")
                return Result.failure(IllegalStateException("账户已被锁定，请${remainingSeconds}秒后再试"))
            } else {
                // 锁定已过期，清除锁定记录
                loginLockouts.remove(ipAddress)
                loginFailedAttempts.remove(ipAddress)
            }
        }

        return Result.success(Unit)
    }

    /**
     * 记录登录失败尝试
     * @param ipAddress 客户端IP地址
     * @return 如果触发锁定返回锁定信息，否则返回 null
     */
    fun recordLoginFailure(ipAddress: String): String? {
        val now = System.currentTimeMillis()
        val windowStart = now - (loginWindowSeconds * 1000)

        // 获取或创建该IP的尝试记录
        val attempts = loginFailedAttempts.computeIfAbsent(ipAddress) { mutableListOf() }

        // 清理过期记录并添加新记录
        synchronized(attempts) {
            attempts.removeIf { it < windowStart }
            attempts.add(now)

            // 检查是否需要锁定
            if (attempts.size >= loginMaxAttempts) {
                val lockoutEndTime = now + (loginLockoutSeconds * 1000)
                loginLockouts[ipAddress] = lockoutEndTime
                logger.warn("登录锁定触发: ip=$ipAddress, attempts=${attempts.size}, lockoutSeconds=$loginLockoutSeconds")
                return "登录失败次数过多，账户已被锁定${loginLockoutSeconds / 60}分钟"
            }
        }

        val remainingAttempts = loginMaxAttempts - attempts.size
        logger.warn("登录失败: ip=$ipAddress, attempts=${attempts.size}/$loginMaxAttempts, remainingAttempts=$remainingAttempts")
        return null
    }

    /**
     * 登录成功时清除失败记录
     * @param ipAddress 客户端IP地址
     */
    fun clearLoginFailures(ipAddress: String) {
        loginFailedAttempts.remove(ipAddress)
        loginLockouts.remove(ipAddress)
    }
}

