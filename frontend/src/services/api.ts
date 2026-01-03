import axios, { AxiosInstance, AxiosError } from 'axios'
import type { ApiResponse, NotificationConfig, NotificationConfigRequest, NotificationConfigUpdateRequest } from '../types'
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
 * 获取当前语言设置（优先从 localStorage 读取，确保获取最新值）
 */
const getCurrentLanguage = (): string => {
  // 优先从 localStorage 读取用户设置的语言（统一使用 i18n_language）
  let savedLanguage = localStorage.getItem('i18n_language')
  
  // 如果 i18n_language 不存在，尝试从 i18nextLng 读取（i18next 默认使用的 key）
  if (!savedLanguage) {
    savedLanguage = localStorage.getItem('i18nextLng')
  }
  
  // 如果设置了具体语言，使用设置的语言
  if (savedLanguage && savedLanguage !== 'auto' && ['zh-CN', 'zh-TW', 'en'].includes(savedLanguage)) {
    return savedLanguage
  }
  
  // 如果设置为 auto 或未设置，使用 i18n 的当前语言
  // 如果 i18n.language 也没有，使用默认值 'en'
  const currentLang = i18n.language || 'en'
  
  // 确保返回的语言格式正确（移除可能的区域代码，如 'en-US' -> 'en'）
  if (currentLang.startsWith('zh-CN')) return 'zh-CN'
  if (currentLang.startsWith('zh-TW') || currentLang.startsWith('zh-HK')) return 'zh-TW'
  if (currentLang.startsWith('en')) return 'en'
  
  return 'en'
}

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
    // 添加语言 Header（每次请求都获取最新值）
    const language = getCurrentLanguage()
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
      apiClient.post<ApiResponse<any[]>>('/system/users/list', {}),
    
    /**
     * 创建用户
     */
    create: (data: { username: string; password: string }) =>
      apiClient.post<ApiResponse<any>>('/system/users/create', data),
    
    /**
     * 更新用户密码
     */
    updatePassword: (data: { userId: number; newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/system/users/update-password', data),
    
    /**
     * 删除用户
     */
    delete: (data: { userId: number }) =>
      apiClient.post<ApiResponse<void>>('/system/users/delete', data),
    
    /**
     * 用户修改自己的密码
     */
    updateOwnPassword: (data: { newPassword: string }) =>
      apiClient.post<ApiResponse<void>>('/system/users/update-own-password', data)
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
      apiClient.post<ApiResponse<{ isFirstUse: boolean }>>('/auth/check-first-use', {}),

    /**
     * 获取 WebSocket 连接票据
     * 返回一个短期有效（30秒）的一次性票据
     */
    getWebSocketTicket: () =>
      apiClient.post<ApiResponse<{ ticket: string }>>('/auth/ws-ticket', {})
  },
  
  /**
   * 账户管理 API
   */
  accounts: {
    /**
     * 导入账户
     */
    import: (data: any) => 
      apiClient.post<ApiResponse<any>>('/accounts/import', data),
    
    /**
     * 更新账户
     */
    update: (data: any) => 
      apiClient.post<ApiResponse<any>>('/accounts/update', data),
    
    /**
     * 删除账户
     */
    delete: (data: { accountId: number }) => 
      apiClient.post<ApiResponse<void>>('/accounts/delete', data),
    
    /**
     * 查询账户列表
     */
    list: () => 
      apiClient.post<ApiResponse<any>>('/accounts/list', {}),
    
    /**
     * 查询账户详情
     */
    detail: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/accounts/detail', data),
    
    /**
     * 查询账户余额
     */
    balance: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/accounts/balance', data),
    
    /**
     * 查询所有账户的仓位列表
     */
    positionsList: () => 
      apiClient.post<ApiResponse<any>>('/accounts/positions/list', {}),
    
    /**
     * 卖出仓位
     */
    sellPosition: (data: any) => 
      apiClient.post<ApiResponse<any>>('/accounts/positions/sell', data),
    
    /**
     * 获取可赎回仓位统计
     */
    getRedeemableSummary: (data: { accountId?: number }) => 
      apiClient.post<ApiResponse<any>>('/accounts/positions/redeemable-summary', data),
    
    /**
     * 赎回仓位
     */
    redeemPositions: (data: any) => 
      apiClient.post<ApiResponse<any>>('/accounts/positions/redeem', data),
    
  },
  
  /**
   * 市场数据 API
   */
  markets: {
    /**
     * 获取市场价格（通过 Gamma API）
     */
    getMarketPrice: (data: { marketId: string; outcomeIndex?: number }) => 
      apiClient.post<ApiResponse<any>>('/markets/price', data),
    
    /**
     * 获取最新价（从订单表获取，供前端下单时显示）
     */
    getLatestPrice: (data: { tokenId: string }) => 
      apiClient.post<ApiResponse<any>>('/markets/latest-price', data)
  },
  
  /**
   * Leader 管理 API
   */
  leaders: {
    /**
     * 添加 Leader
     */
    add: (data: { leaderAddress: string; leaderName?: string; remark?: string; website?: string; category?: string }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/leaders/add', data),
    
    /**
     * 更新 Leader
     */
    update: (data: { leaderId: number; leaderName?: string; remark?: string; website?: string; category?: string }) => 
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
     * 创建跟单配置
     * 支持两种方式：
     * 1. 提供 templateId：从模板填充配置，可以覆盖部分字段
     * 2. 不提供 templateId：手动输入所有配置参数
     */
    create: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/create', data),
    
    /**
     * 更新跟单配置
     */
    update: (data: any) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/update', data),
    
    /**
     * 查询跟单列表
     */
    list: (data: { accountId?: number; leaderId?: number; enabled?: boolean } = {}) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/list', data),
    
    /**
     * 更新跟单状态（兼容旧接口）
     */
    updateStatus: (data: { copyTradingId: number; enabled: boolean }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/update-status', data),
    
    /**
     * 删除跟单
     */
    delete: (data: { copyTradingId: number }) => 
      apiClient.post<ApiResponse<void>>('/copy-trading/configs/delete', data),
    
    /**
     * 查询钱包绑定的跟单配置（兼容旧接口）
     */
    getAccountTemplates: (data: { accountId: number }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/account-templates', data),
    
    /**
     * 查询被过滤订单列表
     */
    getFilteredOrders: (data: {
      copyTradingId: number
      filterType?: string
      page?: number
      limit?: number
      startTime?: number
      endTime?: number
    }) => 
      apiClient.post<ApiResponse<any>>('/copy-trading/configs/filtered-orders', data)
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
      apiClient.post<ApiResponse<any>>('/system/proxy/get', {}),
    
    /**
     * 获取所有代理配置
     */
    list: () =>
      apiClient.post<ApiResponse<any[]>>('/system/proxy/list', {}),
    
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
      apiClient.post<ApiResponse<any>>('/system/proxy/http/save', data),
    
    /**
     * 检查代理是否可用
     */
    check: () =>
      apiClient.post<ApiResponse<{
        success: boolean
        message: string
        responseTime?: number
      }>>('/system/proxy/check', {}),
    
    /**
     * 删除代理配置
     */
    delete: (data: { id: number }) =>
      apiClient.post<ApiResponse<void>>('/system/proxy/delete', data),
    
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
      }>>('/system/proxy/api-health-check', {})
  },
  
  /**
   * 消息推送配置 API
   */
  notifications: {
    /**
     * 获取配置列表
     */
    list: (data?: { type?: string }) =>
      apiClient.post<ApiResponse<NotificationConfig[]>>('/system/notifications/configs/list', data || {}),
    
    /**
     * 获取配置详情
     */
    detail: (data: { id: number }) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/detail', data),
    
    /**
     * 创建配置
     */
    create: (data: NotificationConfigRequest) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/create', data),
    
    /**
     * 更新配置
     */
    update: (data: NotificationConfigUpdateRequest) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/update', data),
    
    /**
     * 更新启用状态
     */
    updateEnabled: (data: { id: number; enabled: boolean }) =>
      apiClient.post<ApiResponse<NotificationConfig>>('/system/notifications/configs/update-enabled', data),
    
    /**
     * 删除配置
     */
    delete: (data: { id: number }) =>
      apiClient.post<ApiResponse<void>>('/system/notifications/configs/delete', data),
    
    /**
     * 测试通知
     */
    test: (data?: { message?: string }) =>
      apiClient.post<ApiResponse<boolean>>('/system/notifications/test', data || {}),
    
    /**
     * 获取 Telegram Chat IDs
     */
    getTelegramChatIds: (data: { botToken: string }) =>
      apiClient.post<ApiResponse<string[]>>('/system/notifications/telegram/get-chat-ids', data)
  },
  
  /**
   * 系统配置 API
   */
  systemConfig: {
    /**
     * 获取系统配置
     */
    get: () =>
      apiClient.post<ApiResponse<import('../types').SystemConfig>>('/system/config/get', {}),
    
    /**
     * 更新 Builder API Key 配置
     */
    updateBuilderApiKey: (data: import('../types').BuilderApiKeyUpdateRequest) =>
      apiClient.post<ApiResponse<import('../types').SystemConfig>>('/system/config/builder-api-key/update', data),
    
    /**
     * 检查 Builder API Key 是否已配置
     */
    checkBuilderApiKey: () =>
      apiClient.post<ApiResponse<{ configured: boolean }>>('/system/config/builder-api-key/check', {}),
    
    /**
     * 更新自动赎回配置
     */
    updateAutoRedeem: (data: { enabled: boolean }) =>
      apiClient.post<ApiResponse<import('../types').SystemConfig>>('/system/config/auto-redeem/update', data),
    
    /**
     * 获取自动赎回状态
     */
    getAutoRedeemStatus: () =>
      apiClient.post<ApiResponse<{ enabled: boolean }>>('/system/config/auto-redeem/status', {})
  },
  
  /**
   * 公告 API
   */
  /**
   * RPC 节点配置 API
   */
  rpcNodes: {
    list: () =>
      apiClient.post<ApiResponse<import('../types').RpcNodeConfig[]>>('/system/rpc-nodes/list', {}),
    
    add: (data: import('../types').RpcNodeAddRequest) =>
      apiClient.post<ApiResponse<import('../types').RpcNodeConfig>>('/system/rpc-nodes/add', data),
    
    update: (data: import('../types').RpcNodeUpdateRequest) =>
      apiClient.post<ApiResponse<import('../types').RpcNodeConfig>>('/system/rpc-nodes/update', data),
    
    delete: (data: { id: number }) =>
      apiClient.post<ApiResponse<void>>('/system/rpc-nodes/delete', data),
    
    updatePriority: (data: { id: number; priority: number }) =>
      apiClient.post<ApiResponse<void>>('/system/rpc-nodes/update-priority', data),
    
    checkHealth: (data: { id?: number }) =>
      apiClient.post<ApiResponse<any>>('/system/rpc-nodes/check-health', data),
    
    validate: (data: import('../types').RpcNodeAddRequest) =>
      apiClient.post<ApiResponse<{ valid: boolean; message: string; responseTimeMs?: number }>>('/system/rpc-nodes/validate', data)
  },
  

  announcements: {
    /**
     * 获取公告列表（最近10条）
     */
    list: (data?: { forceRefresh?: boolean }) =>
      apiClient.post<ApiResponse<{
        list: Array<{
          id: number
          title: string
          body: string
          author: string
          authorAvatarUrl?: string
          createdAt: number
          updatedAt: number
          reactions?: {
            plusOne?: number
            minusOne?: number
            laugh?: number
            confused?: number
            heart?: number
            hooray?: number
            eyes?: number
            rocket?: number
            total?: number
          }
        }>
        hasMore: boolean
        total: number
      }>>('/announcements/list', data || {}),
    
    /**
     * 获取公告详情
     */
    detail: (data: { id?: number; forceRefresh?: boolean }) =>
      apiClient.post<ApiResponse<{
        id: number
        title: string
        body: string
        author: string
        authorAvatarUrl?: string
        createdAt: number
        updatedAt: number
        reactions?: {
          plusOne?: number
          minusOne?: number
          laugh?: number
          confused?: number
          heart?: number
          hooray?: number
          eyes?: number
          rocket?: number
          total?: number
        }
      }>>('/announcements/detail', data)
  }
}

export default apiService

