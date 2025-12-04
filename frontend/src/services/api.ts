import axios, { AxiosInstance, AxiosError } from 'axios'
import type { ApiResponse } from '../types'
import { getToken, setToken, removeToken } from '../utils'
import { wsManager } from './websocket'
import i18n from '../i18n/config'

/**
 * API 基础配置
 * 默认使用相对路径 /api（通过反向代理转发）
 * 如果设置了 VITE_API_URL 环境变量，则使用完整 URL（用于跨域场景）
 */
const getBaseURL = (): string => {
  const envApiUrl = import.meta.env.VITE_API_URL
  if (envApiUrl) {
    // 如果设置了环境变量，使用完整 URL（支持跨域）
    return `${envApiUrl}/api`
  }
  // 否则使用相对路径（通过反向代理转发）
  return '/api'
}

const apiClient: AxiosInstance = axios.create({
  baseURL: getBaseURL(),
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/**
 * 请求拦截器
 */
apiClient.interceptors.request.use(
  (config) => {
    // 从 localStorage 读取 token 并添加到请求头
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 添加语言 Header
    const language = i18n.language || 'en'
    config.headers['X-Language'] = language
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

/**
 * 处理认证错误（自动登出）
 */
const handleAuthError = (code: number) => {
  // 检查是否是认证错误（2001-2999）
  if (code >= 2001 && code < 3000) {
    // 清除 token
    removeToken()
    // 断开 WebSocket 连接
    wsManager.disconnect()
    // 跳转到登录页（避免循环跳转）
    if (window.location.pathname !== '/login' && window.location.pathname !== '/reset-password') {
      window.location.href = '/login'
    }
  }
}

/**
 * 响应拦截器
 */
apiClient.interceptors.response.use(
  (response) => {
    // 检查响应头中是否有新的 token（自动刷新）
    const newToken = response.headers['x-new-token']
    if (newToken) {
      setToken(newToken)
    }
    
    // 检查响应体中的 code，如果是认证错误，自动登出
    // 后端可能返回 200 状态码，但 code 是 2001（认证失败）
    const data = response.data as ApiResponse<any>
    if (data && data.code !== undefined) {
      handleAuthError(data.code)
    }
    
    return response
  },
  (error: AxiosError<ApiResponse<any>>) => {
    if (error.response) {
      const response = error.response
      const data = response.data
      
      // 检查是否是认证错误（2001-2999）
      if (data && data.code !== undefined) {
        handleAuthError(data.code)
      }
      
      console.error('API 错误:', data)
    } else if (error.request) {
      console.error('网络错误:', error.request)
    } else {
      console.error('请求错误:', error.message)
    }
    return Promise.reject(error)
  }
)

/**
 * API 服务
 */
export const apiService = {
  /**
   * 用户管理 API
   */
  users: {
    /**
     * 获取用户列表
     */
    list: () =>
      apiClient.post<ApiResponse<any[]>>('/users/list', {}),
    
    /**
     * 创建用户
     */
    create: (data: { username: string; password: string }) =>
      apiClient.post<ApiResponse<any>>('/users/create', data),
    
    /**
     * 更新用户密码
     */
    updatePassword: (data: { userId: number; newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/users/update-password', data),
    
    /**
     * 删除用户
     */
    delete: (data: { userId: number }) =>
      apiClient.post<ApiResponse<void>>('/users/delete', data),
    
    /**
     * 用户修改自己的密码
     */
    updateOwnPassword: (data: { newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/users/update-own-password', data)
  },
  
  /**
   * 认证 API
   */
  auth: {
    /**
     * 登录
     */
    login: (data: { username: string; password: string }) =>
      apiClient.post<ApiResponse<{ token: string }>>('/auth/login', data),
    
    /**
     * 重置密码
     */
    resetPassword: (data: { resetKey: string; username: string; newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/auth/reset-password', data),
    
    /**
     * 检查是否首次使用
     */
    checkFirstUse: () =>
      apiClient.post<ApiResponse<{ isFirstUse: boolean }>>('/auth/check-first-use', {})
  },
  
  /**
   * 账户管理 API
   */
  accounts: {
    /**
     * 导入账户
     */
    import: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/import', data),
    
    /**
     * 更新账户
     */
    update: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/update', data),
    
    /**
     * 删除账户
     */
    delete: (data: { accountId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/accounts/delete', data),
    
    /**
     * 查询账户列表
     */
    list: () => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/list', {}),
    
    /**
     * 查询账户详情
     */
    detail: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/detail', data),
    
    /**
     * 查询账户余额
     */
    balance: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/balance', data),
    
    /**
     * 查询所有账户的仓位列表
     */
    positionsList: () => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/positions/list', {}),
    
    /**
     * 卖出仓位
     */
    sellPosition: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/positions/sell', data),
    
    /**
     * 获取可赎回仓位统计
     */
    getRedeemableSummary: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/positions/redeemable-summary', data),
    
    /**
     * 赎回仓位
     */
    redeemPositions: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/accounts/positions/redeem', data),
    
  },
  
  /**
   * 市场数据 API
   */
  markets: {
    /**
     * 获取市场价格（通过 Gamma API）
     */
    getMarketPrice: (data: { marketId: string; outcomeIndex?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/markets/price', data),
    
    /**
     * 获取最新价（从订单表获取，供前端下单时显示）
     */
    getLatestPrice: (data: { tokenId: string }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/markets/latest-price', data)
  },
  
  /**
   * Leader 管理 API
   */
  leaders: {
    /**
     * 添加 Leader
     */
    add: (data: { leaderAddress: string; leaderName?: string; category?: string }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/leaders/add', data),
    
    /**
     * 更新 Leader
     */
    update: (data: { leaderId: number; leaderName?: string; category?: string }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/leaders/update', data),
    
    /**
     * 删除 Leader
     */
    delete: (data: { leaderId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/leaders/delete', data),
    
    /**
     * 查询 Leader 列表
     */
    list: (data: { category?: string } = {}) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/leaders/list', data),
    
    /**
     * 查询 Leader 详情
     */
    detail: (data: { leaderId: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/leaders/detail', data)
  },
  
  /**
   * 跟单模板管理 API（子菜单：跟单模板）
   */
  templates: {
    /**
     * 创建模板
     */
    create: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/templates/create', data),
    
    /**
     * 更新模板
     */
    update: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/templates/update', data),
    
    /**
     * 删除模板
     */
    delete: (data: { templateId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/templates/delete', data),
    
    /**
     * 复制模板
     */
    copy: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/templates/copy', data),
    
    /**
     * 查询模板列表
     */
    list: () => 
      apiClient.post<ApiResponse<any>>('/copy-trading/templates/list', {}),
    
    /**
     * 查询模板详情
     */
    detail: (data: { templateId: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/templates/detail', data)
  },
  
  /**
   * 跟单配置管理 API（子菜单：跟单配置）
   */
  copyTrading: {
    /**
     * 创建跟单
     */
    create: (data: { accountId: number; templateId: number; leaderId: number; enabled?: boolean }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/create', data),
    
    /**
     * 查询跟单列表
     */
    list: (data: { accountId?: number; templateId?: number; leaderId?: number; enabled?: boolean } = {}) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/list', data),
    
    /**
     * 更新跟单状态
     */
    updateStatus: (data: { copyTradingId: number; enabled: boolean }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/update-status', data),
    
    /**
     * 删除跟单
     */
    delete: (data: { copyTradingId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/delete', data),
    
    /**
     * 查询钱包绑定的模板
     */
    getAccountTemplates: (data: { accountId: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/account-templates', data)
  },
  
  /**
   * 订单管理 API
   */
  orders: {
    /**
     * 查询跟单订单列表
     */
    list: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/orders/list', data),
    
    /**
     * 取消跟单订单
     */
    cancel: (data: { copyOrderId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/orders/cancel', data)
  },
  
  /**
   * 统计 API
   */
  statistics: {
    /**
     * 获取全局统计
     */
    global: (data: { startTime?: number; endTime?: number } = {}) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/statistics/global', data),
    
    /**
     * 获取 Leader 统计
     */
    leader: (data: { leaderId: number; startTime?: number; endTime?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/statistics/leader', data),
    
    /**
     * 获取分类统计
     */
    category: (data: { category: string; startTime?: number; endTime?: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/statistics/category', data),
    
    /**
     * 获取跟单关系统计详情
     */
    detail: (data: { copyTradingId: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/statistics/detail', data)
  },
  
  /**
   * 订单跟踪 API
   */
  orderTracking: {
    /**
     * 查询订单列表（买入/卖出/匹配）
     */
    list: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/orders/tracking', data)
  },
  
  /**
   * 代理配置 API
   */
  proxyConfig: {
    /**
     * 获取当前代理配置
     */
    get: () =>
      apiClient.post<ApiResponse<any>>('/proxy-config/get', {}),
    
    /**
     * 获取所有代理配置
     */
    list: () =>
      apiClient.post<ApiResponse<any[]>>('/proxy-config/list', {}),
    
    /**
     * 保存 HTTP 代理配置
     */
    saveHttp: (data: {
      enabled: boolean
      host: string
      port: number
      username?: string
      password?: string
    }) =>
      apiClient.post<ApiResponse<any>>('/proxy-config/http/save', data),
    
    /**
     * 检查代理是否可用
     */
    check: () =>
      apiClient.post<ApiResponse<{
        success: boolean
        message: string
        responseTime?: number
      }>>('/proxy-config/check', {}),
    
    /**
     * 删除代理配置
     */
    delete: (data: { id: number }) =>
      apiClient.post<ApiResponse<void>>('/proxy-config/delete', data),
    
    /**
     * 检查所有 API 的健康状态
     */
    checkApiHealth: () =>
      apiClient.post<ApiResponse<{
        apis: Array<{
          name: string
          url: string
          status: string
          message: string
          responseTime?: number
        }>
      }>>('/proxy-config/api-health-check', {})
  }
}

export default apiService

