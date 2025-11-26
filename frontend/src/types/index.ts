/**
 * API 统一响应格式
 */
export interface ApiResponse<T> {
  code: number
  data: T | null
  msg: string
}

/**
 * 账户信息
 */
export interface Account {
  id: number
  walletAddress: string
  accountName?: string
  isDefault: boolean
  apiKeyConfigured: boolean
  apiSecretConfigured: boolean
  apiPassphraseConfigured: boolean
  balance?: string
  totalOrders?: number
  totalPnl?: string
  activeOrders?: number
  completedOrders?: number
  positionCount?: number
}

/**
 * 账户列表响应
 */
export interface AccountListResponse {
  list: Account[]
  total: number
}

/**
 * 账户导入请求
 */
export interface AccountImportRequest {
  privateKey: string
  walletAddress: string
  accountName?: string
  isDefault?: boolean
}

/**
 * 账户更新请求
 */
export interface AccountUpdateRequest {
  accountId: number
  accountName?: string
  isDefault?: boolean
}

/**
 * Leader 信息
 */
export interface Leader {
  id: number
  leaderAddress: string
  leaderName?: string
  accountId?: number
  category?: string
  enabled: boolean
  copyRatio: string
  totalOrders?: number
  totalPnl?: string
}

/**
 * Leader 列表响应
 */
export interface LeaderListResponse {
  list: Leader[]
  total: number
}

/**
 * Leader 添加请求
 */
export interface LeaderAddRequest {
  leaderAddress: string
  leaderName?: string
  accountId?: number
  category?: string
  enabled?: boolean
}

/**
 * 跟单配置
 */
export interface CopyTradingConfig {
  copyMode: 'RATIO' | 'FIXED'
  copyRatio: string
  fixedAmount?: string
  maxOrderSize: string
  minOrderSize: string
  maxDailyLoss: string
  maxDailyOrders: number
  priceTolerance: string
  delaySeconds: number
  pollIntervalSeconds: number
  useWebSocket: boolean
  websocketReconnectInterval: number
  websocketMaxRetries: number
  enabled: boolean
}

/**
 * 跟单订单
 */
export interface CopyOrder {
  id: number
  accountId: number
  leaderId: number
  leaderAddress: string
  leaderName?: string
  marketId: string
  category: string
  side: 'BUY' | 'SELL'
  price: string
  size: string
  copyRatio: string
  orderId?: string
  status: string
  filledSize: string
  pnl?: string
  createdAt: number
}

/**
 * 订单列表响应
 */
export interface OrderListResponse {
  list: CopyOrder[]
  total: number
  page: number
  limit: number
}

/**
 * 统计信息
 */
export interface Statistics {
  totalOrders: number
  totalPnl: string
  winRate: string
  avgPnl: string
  maxProfit: string
  maxLoss: string
}

/**
 * 账户仓位信息
 */
export interface AccountPosition {
  accountId: number
  accountName?: string
  walletAddress: string
  proxyAddress: string
  marketId: string
  marketTitle?: string
  marketSlug?: string
  marketIcon?: string  // 市场图标 URL
  side: string  // YES 或 NO
  quantity: string
  avgPrice: string
  currentPrice: string
  currentValue: string
  initialValue: string
  pnl: string
  percentPnl: string
  realizedPnl?: string
  percentRealizedPnl?: string
  redeemable: boolean
  mergeable: boolean
  endDate?: string
  isCurrent: boolean  // true: 当前仓位（有持仓），false: 历史仓位（已平仓）
}

/**
 * 仓位列表响应
 */
export interface PositionListResponse {
  currentPositions: AccountPosition[]
  historyPositions: AccountPosition[]
}

/**
 * 仓位推送消息类型
 */
export type PositionPushMessageType = 'FULL' | 'INCREMENTAL'

/**
 * 仓位推送消息
 */
export interface PositionPushMessage {
  type: PositionPushMessageType  // 消息类型：FULL（全量）或 INCREMENTAL（增量）
  timestamp: number  // 消息时间戳
  currentPositions?: AccountPosition[]  // 当前仓位列表（全量或增量）
  historyPositions?: AccountPosition[]  // 历史仓位列表（全量或增量）
  removedPositionKeys?: string[]  // 已删除的仓位键（仅增量推送时使用）
}

/**
 * 获取仓位唯一键
 */
export function getPositionKey(position: AccountPosition): string {
  return `${position.accountId}-${position.marketId}-${position.side}`
}

