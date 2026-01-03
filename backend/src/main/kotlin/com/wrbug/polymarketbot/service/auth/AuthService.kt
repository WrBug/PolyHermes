package com.wrbug.polymarketbot.service.auth

import com.wrbug.polymarketbot.dto.CheckFirstUseResponse
import com.wrbug.polymarketbot.dto.LoginResponse
import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import com.wrbug.polymarketbot.service.common.RateLimitService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 认证服务
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtUtils: JwtUtils,
    private val rateLimitService: RateLimitService
) {
    
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()
    
    @Value("\${admin.reset-password.key}")
    private lateinit var resetPasswordKey: String
    
    /**
     * 登录（带IP限速保护）
     */
    fun login(username: String, password: String, ipAddress: String): Result<LoginResponse> {
        return try {
            // 检查登录频率限制
            rateLimitService.checkLoginRateLimit(ipAddress).fold(
                onSuccess = { },
                onFailure = { e ->
                    return Result.failure(IllegalStateException(e.message ?: "登录频率限制"))
                }
            )

            val user = userRepository.findByUsername(username)
            if (user == null) {
                // 记录失败尝试
                val lockoutMsg = rateLimitService.recordLoginFailure(ipAddress)
                if (lockoutMsg != null) {
                    return Result.failure(IllegalStateException(lockoutMsg))
                }
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message))
            }

            // 验证密码
            if (!passwordEncoder.matches(password, user.password)) {
                // 记录失败尝试
                val lockoutMsg = rateLimitService.recordLoginFailure(ipAddress)
                if (lockoutMsg != null) {
                    return Result.failure(IllegalStateException(lockoutMsg))
                }
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message))
            }

            // 登录成功，清除失败记录
            rateLimitService.clearLoginFailures(ipAddress)

            // 生成JWT token（包含tokenVersion，用于使修改密码后的旧token失效）
            val token = jwtUtils.generateToken(username, user.tokenVersion)

            logger.info("用户登录成功：username=$username")
            Result.success(LoginResponse(token = token))
        } catch (e: Exception) {
            logger.error("登录异常：username=$username", e)
            Result.failure(e)
        }
    }
    
    /**
     * 重置密码
     */
    @Transactional
    fun resetPassword(
        resetKey: String,
        username: String,
        newPassword: String,
        request: HttpServletRequest
    ): Result<Unit> {
        return try {
            // 先检查频率限制（全局限制，不按IP）
            rateLimitService.checkResetPasswordRateLimit().fold(
                onSuccess = { },
                onFailure = { e ->
                    logger.warn("重置密码频率限制触发：username=$username")
                    return Result.failure(IllegalStateException("重置失败"))
                }
            )
            
            // 验证重置密钥
            if (resetKey != resetPasswordKey) {
                logger.warn("重置密码失败：重置密钥错误，username=$username")
                return Result.failure(IllegalArgumentException("重置失败"))
            }
            
            // 验证密码强度
            if (!checkPasswordStrength(newPassword)) {
                logger.warn("重置密码失败：密码强度不符合要求，username=$username")
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_PASSWORD_WEAK.message))
            }
            
            // 检查用户是否存在
            val existingUser = userRepository.findByUsername(username)
            
            if (existingUser != null) {
                // 用户存在，更新密码并递增tokenVersion（使所有旧token失效）
                val encodedPassword = passwordEncoder.encode(newPassword)
                val updatedUser = existingUser.copy(
                    password = encodedPassword,
                    tokenVersion = existingUser.tokenVersion + 1,  // 递增tokenVersion
                    updatedAt = System.currentTimeMillis()
                )
                userRepository.save(updatedUser)
                logger.info("密码重置成功：username=$username, tokenVersion=${updatedUser.tokenVersion}")
            } else {
                // 用户不存在，检查是否是首次使用
                val isFirstUse = userRepository.count() == 0L
                if (isFirstUse) {
                    // 首次使用，创建新用户（设置为默认账户）
                    val encodedPassword = passwordEncoder.encode(newPassword)
                    val newUser = User(
                        username = username,
                        password = encodedPassword,
                        isDefault = true,  // 首次创建的用户为默认账户
                        tokenVersion = 0,  // 初始tokenVersion为0
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    userRepository.save(newUser)
                    logger.info("首次使用，创建默认账户成功：username=$username")
                } else {
                    // 不是首次使用，用户不存在
                    logger.warn("重置密码失败：用户不存在，username=$username")
                    return Result.failure(IllegalArgumentException("重置失败"))
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("重置密码异常：username=$username", e)
            Result.failure(e)
        }
    }
    
    /**
     * 刷新token
     */
    fun refreshToken(token: String): Result<String> {
        return try {
            if (!jwtUtils.validateToken(token)) {
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            }
            
            if (!jwtUtils.isTokenExpiring(token)) {
                // 不需要刷新，返回原token
                return Result.success(token)
            }
            
            // 获取用户名并生成新token
            val username = jwtUtils.getUsernameFromToken(token)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            
            // 从数据库获取用户的tokenVersion
            val user = userRepository.findByUsername(username)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            
            val newToken = jwtUtils.generateToken(username, user.tokenVersion)
            logger.debug("Token刷新成功：username=$username")
            Result.success(newToken)
        } catch (e: Exception) {
            logger.error("刷新token异常", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查是否首次使用（数据库中是否有用户）
     */
    fun isFirstUse(): Boolean {
        return userRepository.count() == 0L
    }
    
    /**
     * 验证密码强度
     * 至少6位
     */
    private fun checkPasswordStrength(password: String): Boolean {
        return password.length >= 6
    }
    
    /**
     * 获取客户端IP地址
     */
    private fun getClientIpAddress(request: HttpServletRequest): String {
        var ip = request.getHeader("X-Forwarded-For")
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("X-Real-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.remoteAddr
        }
        // 处理多个IP的情况（X-Forwarded-For可能包含多个IP）
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim()
        }
        return ip
    }
}

