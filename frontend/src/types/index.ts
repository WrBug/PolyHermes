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
  proxyAddress: string  // Polymarket 代理钱包地址
  accountName?: string
  isEnabled?: boolean  // 是否启用
  walletType?: string  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
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
  walletType?: string  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
}

/**
 * 账户更新请求
 */
export interface AccountUpdateRequest {
  accountId: number
  accountName?: string
}

/**
 * Leader 信息
 */
export interface Leader {
  id: number
  leaderAddress: string
  leaderName?: string
  category?: string
  remark?: string  // Leader 备注（可选）
  website?: string  // Leader 网站（可选）
  copyTradingCount: number
  totalOrders?: number
  totalPnl?: string
  createdAt: number
  updatedAt: number
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
  category?: string
}

/**
 * Leader 更新请求
 */
export interface LeaderUpdateRequest {
  leaderId: number
  leaderName?: string
  category?: string
}

/**
 * 跟单模板
 */
export interface CopyTradingTemplate {
  id: number
  templateName: string
  copyMode: 'RATIO' | 'FIXED'
  copyRatio: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders: number
  priceTolerance: string
  supportSell: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  pushFilteredOrders?: boolean  // 推送已过滤订单（默认关闭）
  createdAt: number
  updatedAt: number
}

/**
 * 模板列表响应
 */
export interface TemplateListResponse {
  list: CopyTradingTemplate[]
  total: number
}

/**
 * 模板创建请求
 */
export interface TemplateCreateRequest {
  templateName: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 模板更新请求
 */
export interface TemplateUpdateRequest {
  templateId: number
  templateName?: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 模板复制请求
 */
export interface TemplateCopyRequest {
  templateId: number
  templateName: string
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyOrders?: number
  priceTolerance?: string
  supportSell?: boolean
}

/**
 * 跟单配置（独立配置，不再绑定模板）
 */
export interface CopyTrading {
  id: number
  accountId: number
  accountName?: string
  walletAddress: string
  leaderId: number
  leaderName?: string
  leaderAddress: string
  enabled: boolean
  // 跟单配置参数
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
  supportSell: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  maxPositionCount?: number  // 最大仓位数量，NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders: boolean  // 推送失败订单（默认关闭）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
  createdAt: number
  updatedAt: number
}

/**
 * 跟单列表响应
 */
export interface CopyTradingListResponse {
  list: CopyTrading[]
  total: number
}

/**
 * 跟单创建请求
 * 所有配置参数都需要手动输入，模板仅用于前端快速填充表单
 */
export interface CopyTradingCreateRequest {
  accountId: number
  leaderId: number
  enabled?: boolean
  // 跟单配置参数
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  priceTolerance?: string
  delaySeconds?: number
  pollIntervalSeconds?: number
  useWebSocket?: boolean
  websocketReconnectInterval?: number
  websocketMaxRetries?: number
  supportSell?: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  maxPositionCount?: number  // 最大仓位数量，NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders?: boolean  // 推送失败订单（可选）
  pushFilteredOrders?: boolean  // 推送已过滤订单（可选）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
}

/**
 * 跟单更新请求
 */
export interface CopyTradingUpdateRequest {
  copyTradingId: number
  enabled?: boolean
  // 跟单配置参数（可选，只更新提供的字段）
  copyMode?: 'RATIO' | 'FIXED'
  copyRatio?: string
  fixedAmount?: string
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  priceTolerance?: string
  delaySeconds?: number
  pollIntervalSeconds?: number
  useWebSocket?: boolean
  websocketReconnectInterval?: number
  websocketMaxRetries?: number
  supportSell?: boolean
  // 过滤条件
  minOrderDepth?: string
  maxSpread?: string
  minPrice?: string  // 最低价格（可选），NULL表示不限制最低价
  maxPrice?: string  // 最高价格（可选），NULL表示不限制最高价
  // 最大仓位配置
  maxPositionValue?: string  // 最大仓位金额（USDC），NULL表示不启用
  maxPositionCount?: number  // 最大仓位数量，NULL表示不启用
  // 关键字过滤配置
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'  // 关键字过滤模式
  keywords?: string[]  // 关键字列表，当keywordFilterMode为DISABLED时为null
  // 新增配置字段
  configName?: string  // 配置名（可选，但提供时必须非空）
  pushFailedOrders?: boolean  // 推送失败订单（可选）
  pushFilteredOrders?: boolean  // 推送已过滤订单（可选）
  maxMarketEndDate?: number  // 市场截止时间限制（毫秒时间戳），仅跟单截止时间小于此时间的订单，NULL表示不启用
}

/**
 * 钱包绑定的模板信息
 */
export interface AccountTemplate {
  templateId: number
  templateName: string
  copyTradingId: number
  leaderId: number
  leaderName?: string
  leaderAddress: string
  enabled: boolean
}

/**
 * 钱包绑定的模板列表响应
 */
export interface AccountTemplatesResponse {
  list: AccountTemplate[]
  total: number
}

/**
 * 跟单订单
 */
export interface CopyOrder {
  id: number
  accountId: number
  templateId: number
  copyTradingId: number
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
  marketSlug?: string  // 显示用的 slug
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketIcon?: string  // 市场图标 URL
  side: string  // 结果名称（如 "YES", "NO", "Pakistan" 等）
  outcomeIndex?: number  // 结果索引（0, 1, 2...），用于计算 tokenId
  quantity: string  // 显示用的数量（可能被截位）
  originalQuantity?: string  // 原始数量（保留完整精度，用于100%出售）
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
 * 仓位卖出请求
 */
export interface PositionSellRequest {
  accountId: number
  marketId: string
  side: string  // 结果名称（如 "YES", "NO", "Pakistan" 等）
  outcomeIndex?: number  // 结果索引（0, 1, 2...），用于计算 tokenId（推荐提供）
  orderType: 'MARKET' | 'LIMIT'
  quantity?: string  // 卖出数量（可选，手动输入时使用）
  percent?: string  // 卖出百分比（可选，BigDecimal字符串，支持小数，0-100之间，选择百分比按钮时使用）
  price?: string  // 限价订单必需
}

/**
 * 仓位卖出响应
 */
export interface PositionSellResponse {
  orderId: string
  marketId: string
  side: string
  orderType: string
  quantity: string
  price?: string
  status: string
  createdAt: number
}

/**
 * 市场价格请求
 */
export interface MarketPriceRequest {
  marketId: string
}

/**
 * 市场当前价格响应
 */
export interface MarketPriceResponse {
  marketId: string
  currentPrice: string
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

/**
 * Polymarket 订单消息（来自 WebSocket User Channel）
 */
export interface OrderMessage {
  asset_id: string
  associate_trades?: string[]
  event_type: string  // "order"
  id: string  // order id
  market: string  // condition ID of market
  order_owner: string  // owner of order
  original_size: string  // original order size
  outcome: string  // outcome
  owner: string  // owner of orders
  price: string  // price of order
  side: string  // BUY/SELL
  size_matched: string  // size of order that has been matched
  timestamp: string  // time of event
  type: string  // PLACEMENT/UPDATE/CANCELLATION
}

/**
 * 订单详情（通过 API 获取）
 */
export interface OrderDetail {
  id: string  // 订单 ID
  market: string  // 市场 ID (condition ID)
  side: string  // BUY/SELL
  price: string  // 价格
  size: string  // 订单大小
  filled: string  // 已成交数量
  status: string  // 订单状态
  createdAt: string  // 创建时间（ISO 8601 格式）
  marketName?: string  // 市场名称
  marketSlug?: string  // 市场 slug
  marketIcon?: string  // 市场图标
}

/**
 * 订单推送消息
 */
export interface OrderPushMessage {
  accountId: number
  accountName: string
  order: OrderMessage  // 订单信息（来自 WebSocket）
  orderDetail?: OrderDetail  // 订单详情（通过 API 获取）
  timestamp?: number  // 推送时间戳
  // 跟单相关字段（可选，仅在跟单触发的订单时提供）
  leaderName?: string  // Leader 名称（备注）
  configName?: string  // 跟单配置名
}

/**
 * 账户赎回仓位项（包含账户ID）
 */
export interface AccountRedeemPositionItem {
  accountId: number
  marketId: string
  outcomeIndex: number
  side?: string
}

/**
 * 仓位赎回请求（支持多账户）
 */
export interface PositionRedeemRequest {
  positions: AccountRedeemPositionItem[]
}

/**
 * 赎回的仓位信息
 */
export interface RedeemedPositionInfo {
  marketId: string
  side: string
  outcomeIndex: number
  quantity: string
  value: string
}

/**
 * 账户赎回交易信息
 */
export interface AccountRedeemTransaction {
  accountId: number
  accountName?: string
  transactionHash: string
  positions: RedeemedPositionInfo[]
}

/**
 * 仓位赎回响应
 */
export interface PositionRedeemResponse {
  transactions: AccountRedeemTransaction[]
  totalRedeemedValue: string
  createdAt: number
}

/**
 * 可赎回仓位信息
 */
export interface RedeemablePositionInfo {
  accountId: number
  accountName?: string
  marketId: string
  marketTitle?: string
  side: string
  outcomeIndex: number
  quantity: string
  value: string
}

/**
 * 可赎回仓位统计响应
 */
export interface RedeemablePositionsSummary {
  totalCount: number
  totalValue: string
  positions: RedeemablePositionInfo[]
}

/**
 * 跟单关系统计信息
 */
export interface CopyTradingStatistics {
  copyTradingId: number
  accountId: number
  accountName: string | null
  leaderId: number
  leaderName: string | null
  enabled: boolean
  
  // 买入统计
  totalBuyQuantity: string
  totalBuyOrders: number
  totalBuyAmount: string
  avgBuyPrice: string
  
  // 卖出统计
  totalSellQuantity: string
  totalSellOrders: number
  totalSellAmount: string
  
  // 持仓统计
  currentPositionQuantity: string
  currentPositionValue: string  // 当前实现总是返回 "0"，保留用于未来扩展
  
  // 盈亏统计
  totalRealizedPnl: string
  totalUnrealizedPnl: string
  totalPnl: string
  totalPnlPercent: string
}

/**
 * 买入订单信息
 */
export interface BuyOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  side: string
  quantity: string
  price: string
  amount: string
  matchedQuantity: string
  remainingQuantity: string
  status: 'filled' | 'partially_matched' | 'fully_matched'
  createdAt: number
}

/**
 * 卖出订单信息
 */
export interface SellOrderInfo {
  orderId: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  side: string
  quantity: string
  price: string
  amount: string
  realizedPnl: string
  status?: string  // 卖出状态（filled, partially_matched, fully_matched）
  createdAt: number
}

/**
 * 匹配订单信息
 */
export interface MatchedOrderInfo {
  sellOrderId: string
  buyOrderId: string
  marketId?: string  // 市场ID
  marketTitle?: string  // 市场名称
  marketSlug?: string  // 市场 slug（用于显示）
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string  // 市场分类（sports, crypto 等）
  matchedQuantity: string
  buyPrice: string
  sellPrice: string
  realizedPnl: string
  matchedAt: number
}

/**
 * 订单跟踪列表响应
 */
export interface OrderTrackingListResponse {
  list: BuyOrderInfo[] | SellOrderInfo[] | MatchedOrderInfo[]
  total: number
  page: number
  limit: number
}

/**
 * 订单跟踪查询请求
 */
export interface OrderTrackingRequest {
  copyTradingId: number
  type: 'buy' | 'sell' | 'matched'
  page?: number
  limit?: number
  marketId?: string
  marketTitle?: string
  status?: string
  sellOrderId?: string
  buyOrderId?: string
}

/**
 * 按市场分组的订单查询请求
 */
export interface MarketGroupedOrdersRequest {
  copyTradingId: number
  type: 'buy' | 'sell'
  page?: number
  limit?: number
  marketId?: string
  marketTitle?: string
}

/**
 * 单个市场的订单统计信息
 */
export interface MarketOrderStats {
  count: number
  totalAmount: string  // 总金额
  totalPnl?: string  // 总盈亏（买入订单未实现盈亏，此字段为空）
  fullyMatched: boolean  // 是否全部成交
  fullyMatchedCount: number  // 完全成交的订单数
  partiallyMatchedCount: number  // 部分成交的订单数
  filledCount: number  // 未成交的订单数
}

/**
 * 单个市场分组的响应数据
 */
export interface MarketOrderGroup {
  marketId: string
  marketTitle?: string
  marketSlug?: string  // 显示用的 slug
  eventSlug?: string  // 跳转用的 slug（从 events[0].slug 获取）
  marketCategory?: string
  stats: MarketOrderStats
  orders: BuyOrderInfo[] | SellOrderInfo[]  // 订单列表
}

/**
 * 按市场分组的订单列表响应
 */
export interface MarketGroupedOrdersResponse {
  list: MarketOrderGroup[]
  total: number  // 市场总数
  page: number
  limit: number
}

/**
 * 被过滤订单信息
 */
export interface FilteredOrder {
  id: number
  copyTradingId: number
  accountId: number
  accountName?: string
  leaderId: number
  leaderName?: string
  leaderTradeId: string
  marketId: string
  marketTitle?: string
  marketSlug?: string
  side: 'BUY' | 'SELL'
  outcomeIndex?: number
  outcome?: string
  price: string
  size: string
  calculatedQuantity?: string
  filterReason: string
  filterType: string
  createdAt: number
}

/**
 * 被过滤订单列表请求
 */
export interface FilteredOrderListRequest {
  copyTradingId: number
  filterType?: string
  page?: number
  limit?: number
  startTime?: number
  endTime?: number
}

/**
 * 被过滤订单列表响应
 */
export interface FilteredOrderListResponse {
  list: FilteredOrder[]
  total: number
  page: number
  limit: number
}

/**
 * 消息推送配置
 */
export interface NotificationConfig {
  id?: number
  type: string  // telegram、discord、slack 等
  name: string  // 配置名称
  enabled: boolean  // 是否启用
  config: {
    botToken?: string  // Telegram Bot Token
    chatIds?: string[]  // Telegram Chat IDs
    [key: string]: any  // 其他配置字段
  }
  createdAt?: number
  updatedAt?: number
}

/**
 * 通知配置请求
 */
export interface NotificationConfigRequest {
  type: string
  name: string
  enabled?: boolean
  config: {
    botToken?: string
    chatIds?: string[] | string  // 支持数组或逗号分隔的字符串
    [key: string]: any
  }
}

/**
 * 通知配置更新请求
 */
/**
 * 系统配置响应
 */
export interface SystemConfig {
  builderApiKeyConfigured: boolean
  builderSecretConfigured: boolean
  builderPassphraseConfigured: boolean
  builderApiKeyDisplay?: string  // Builder API Key 显示值（部分显示）
  builderSecretDisplay?: string  // Builder Secret 显示值（部分显示）
  builderPassphraseDisplay?: string  // Builder Passphrase 显示值（部分显示）
  autoRedeemEnabled: boolean  // 自动赎回（系统级别配置，默认开启）
}

/**
 * Builder API Key 更新请求
 */
export interface BuilderApiKeyUpdateRequest {
  builderApiKey?: string
  builderSecret?: string
  builderPassphrase?: string
}

export interface NotificationConfigUpdateRequest {
  id: number
  type: string
  name: string
  enabled?: boolean
  config: {
    botToken?: string
    chatIds?: string[] | string
    [key: string]: any
  }
}


/**
 * RPC 节点配置类型
 */
export interface RpcNodeConfig {
  id: number
  providerType: 'ALCHEMY' | 'INFURA' | 'QUICKNODE' | 'CHAINSTACK' | 'GETBLOCK' | 'CUSTOM' | 'PUBLIC'
  name: string
  httpUrl: string
  wsUrl?: string
  apiKeyMasked?: string  // 脱敏后的 API Key
  enabled: boolean
  priority: number
  lastCheckTime?: number
  lastCheckStatus?: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
  responseTimeMs?: number
  createdAt: number
  updatedAt: number
}

/**
 * 添加 RPC 节点请求
 */
export interface RpcNodeAddRequest {
  providerType: string
  name: string
  apiKey?: string  // 主流服务商需要
  httpUrl?: string  // CUSTOM 需要
  wsUrl?: string
}

/**
 * 更新 RPC 节点请求
 */
export interface RpcNodeUpdateRequest {
  id: number
  name?: string
  enabled?: boolean
  priority?: number
}

/**
 * 节点健康检查结果
 */
export interface NodeCheckResult {
  status: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
  message: string
  checkTime: number
  responseTimeMs?: number
  blockNumber?: string
}
