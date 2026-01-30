/**
 * 回测相关类型定义
 */

/**
 * 回测任务创建请求
 */
export interface BacktestCreateRequest {
  taskName: string
  leaderId: number
  initialBalance: string
  backtestDays: number  // 1-30
  // 跟单配置
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  priceTolerance?: string  // 百分比
  delaySeconds?: number
  supportSell?: boolean
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string
  maxPrice?: string
  maxPositionValue?: string
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'
  keywords?: string[]
  maxMarketEndDate?: number | null
}

/**
 * 回测任务列表请求
 */
export interface BacktestListRequest {
  leaderId?: number
  status?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
  sortBy?: 'profitAmount' | 'profitRate' | 'createdAt'
  sortOrder?: 'asc' | 'desc'
  page: number
  size: number
}

/**
 * 回测任务详情请求
 */
export interface BacktestDetailRequest {
  id: number
}

/**
 * 回测交易记录列表请求
 */
export interface BacktestTradeListRequest {
  taskId: number
  page: number
  size: number
}

/**
 * 回测进度查询请求
 */
export interface BacktestProgressRequest {
  id: number
}

/**
 * 回测任务停止请求
 */
export interface BacktestStopRequest {
  id: number
}

/**
 * 回测任务删除请求
 */
export interface BacktestDeleteRequest {
  id: number
}

/**
 * 回测任务列表响应
 */
export interface BacktestListResponse {
  list: BacktestTaskDto[]
  total: number
  page: number
  size: number
}

/**
 * 回测任务详情响应
 */
export interface BacktestDetailResponse {
  task: BacktestTaskDto
  config: BacktestConfigDto
  statistics: BacktestStatisticsDto
}

/**
 * 回测交易记录列表响应
 */
export interface BacktestTradeListResponse {
  list: BacktestTradeDto[]
  total: number
  page: number
  size: number
}

/**
 * 回测进度响应
 */
export interface BacktestProgressResponse {
  progress: number  // 0-100
  currentBalance: string
  totalTrades: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
}

/**
 * 回测任务 DTO
 */
export interface BacktestTaskDto {
  id: number
  taskName: string
  leaderId: number
  leaderName: string | null
  leaderAddress: string | null
  initialBalance: string
  finalBalance: string | null
  profitAmount: string | null
  profitRate: string | null  // 百分比
  backtestDays: number
  startTime: number
  endTime: number | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
  progress: number  // 0-100
  totalTrades: number
  createdAt: number
  executionStartedAt: number | null
  executionFinishedAt: number | null
}

/**
 * 回测配置 DTO
 */
export interface BacktestConfigDto {
  copyMode: 'RATIO' | 'FIXED'
  copyRatio: string
  fixedAmount: string | null
  maxOrderSize: string
  minOrderSize: string
  maxDailyLoss: string
  maxDailyOrders: number
  priceTolerance: string  // 百分比
  delaySeconds: number
  supportSell: boolean
  minOrderDepth: string | null
  maxSpread: string | null
  minPrice: string | null
  maxPrice: string | null
  maxPositionValue: string | null
  keywordFilterMode: 'DISABLED' | 'WHITELIST' | 'BLACKLIST' | null
  keywords: string[] | null
  maxMarketEndDate: number | null
}

/**
 * 回测统计 DTO
 */
export interface BacktestStatisticsDto {
  totalTrades: number  // 总交易笔数
  buyTrades: number  // 买入笔数
  sellTrades: number  // 卖出笔数
  winTrades: number  // 盈利交易笔数
  lossTrades: number  // 亏损交易笔数
  winRate: string  // 胜率 (百分比)
  maxProfit: string  // 最大单笔盈利
  maxLoss: string  // 最大单笔亏损
  maxDrawdown: string  // 最大回撤
  avgHoldingTime: number | null  // 平均持仓时间 (毫秒)
}

/**
 * 回测交易记录 DTO
 */
export interface BacktestTradeDto {
  id: number
  tradeTime: number
  marketId: string
  marketTitle: string | null
  side: 'BUY' | 'SELL' | 'SETTLEMENT'
  outcome: string  // YES/NO 或 outcomeIndex
  outcomeIndex: number | null
  quantity: string
  price: string
  amount: string
  fee: string
  profitLoss: string | null  // 仅卖出和结算时有值
  balanceAfter: string
  leaderTradeId: string | null
}

