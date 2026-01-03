import { useEffect, useState, useMemo } from 'react'
import { Card, Table, Tag, message, Space, Input, Radio, Select, Button, Row, Col, Empty, Modal, Form, Descriptions } from 'antd'
import { SearchOutlined, AppstoreOutlined, UnorderedListOutlined, UpOutlined, DownOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { apiService } from '../services/api'
import type { AccountPosition, Account, PositionPushMessage, PositionSellRequest, MarketPriceResponse, RedeemablePositionsSummary, PositionRedeemRequest } from '../types'
import { getPositionKey } from '../types'
import { useMediaQuery } from 'react-responsive'
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import { wsManager } from '../services/websocket'
import { formatUSDC } from '../utils'

type PositionFilter = 'current' | 'historical'
type ViewMode = 'card' | 'list'

const PositionList: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [currentPositions, setCurrentPositions] = useState<AccountPosition[]>([])
  const [historyPositions, setHistoryPositions] = useState<AccountPosition[]>([])
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(false)
  const [accountsLoading, setAccountsLoading] = useState(false)
  const [searchKeyword, setSearchKeyword] = useState('')
  const [positionFilter, setPositionFilter] = useState<PositionFilter>('current')
  const [selectedAccountId, setSelectedAccountId] = useState<number | undefined>(undefined)
  const [viewMode, setViewMode] = useState<ViewMode>(isMobile ? 'card' : 'list')
  const [expandedCards, setExpandedCards] = useState<Set<string>>(new Set())
  const [sellModalVisible, setSellModalVisible] = useState(false)
  const [selectedPosition, setSelectedPosition] = useState<AccountPosition | null>(null)
  const [marketPrice, setMarketPrice] = useState<MarketPriceResponse | null>(null)
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT'>('LIMIT')
  const [sellQuantity, setSellQuantity] = useState<string>('')
  const [limitPrice, setLimitPrice] = useState<string>('')
  const [selectedPercent, setSelectedPercent] = useState<string | null>(null)  // 记录选择的百分比（字符串格式）
  const [form] = Form.useForm()
  const [submitting, setSubmitting] = useState(false)
  const [wsConnected, setWsConnected] = useState(false)
  const [redeemModalVisible, setRedeemModalVisible] = useState(false)
  const [redeemableSummary, setRedeemableSummary] = useState<RedeemablePositionsSummary | null>(null)
  const [loadingRedeemableSummary, setLoadingRedeemableSummary] = useState(false)
  const [redeeming, setRedeeming] = useState(false)
  
  useEffect(() => {
    fetchAccounts()
    // 完全依赖 WebSocket 推送，不主动请求接口
    // 连接建立后会立即收到全量数据推送
    setLoading(true)  // 显示加载状态，等待 WebSocket 全量推送
    
    // 监听连接状态（WebSocket 连接在 App.tsx 中全局初始化，全局共享）
    const removeListener = wsManager.onConnectionChange((connected) => {
      setWsConnected(connected)
    })
    
    // 获取当前连接状态
    setWsConnected(wsManager.isConnected())
    
    return () => {
      removeListener()
    }
  }, [])
  
  // 当仓位数据变化时，更新可赎回统计
  useEffect(() => {
    if (currentPositions.length > 0) {
      fetchRedeemableSummary()
    }
  }, [currentPositions, selectedAccountId])
  
  // 获取可赎回仓位统计
  const fetchRedeemableSummary = async () => {
    setLoadingRedeemableSummary(true)
    try {
      const response = await apiService.accounts.getRedeemableSummary({ accountId: selectedAccountId })
      if (response.data.code === 0 && response.data.data) {
        setRedeemableSummary(response.data.data)
      }
    } catch (error: any) {
      console.error('获取可赎回统计失败:', error)
    } finally {
      setLoadingRedeemableSummary(false)
    }
  }
  
  // 处理赎回按钮点击
  const handleRedeemClick = async () => {
    await fetchRedeemableSummary()
    setRedeemModalVisible(true)
  }
  
  // 提交赎回
  const handleRedeemSubmit = async () => {
    if (!redeemableSummary || redeemableSummary.positions.length === 0) {
      message.warning('没有可赎回的仓位')
      return
    }
    
    setRedeeming(true)
    try {
      const request: PositionRedeemRequest = {
        positions: redeemableSummary.positions.map(pos => ({
          accountId: pos.accountId,
          marketId: pos.marketId,
          outcomeIndex: pos.outcomeIndex,
          side: pos.side
        }))
      }
      
      const response = await apiService.accounts.redeemPositions(request)
      if (response.data.code === 0 && response.data.data) {
        const transactions = response.data.data.transactions || []
        const txHashes = transactions.map((tx: any) => tx.transactionHash.substring(0, 10) + '...').join(', ')
        message.success(`赎回成功！共 ${transactions.length} 个账户，交易哈希: ${txHashes}`)
        setRedeemModalVisible(false)
        // 刷新可赎回统计
        await fetchRedeemableSummary()
      } else {
        // 检查是否是 Builder API Key 未配置的错误
        if (response.data.code === 2014 || response.data.msg?.includes('Builder API Key 未配置')) {
          message.error({
            content: response.data.msg || 'Builder API Key 未配置',
            duration: 5,
          })
          // 延迟跳转，让用户看到错误消息
          setTimeout(() => {
            navigate('/system-settings/builder-api-key')
          }, 1500)
        } else {
          message.error(response.data.msg || '赎回失败')
        }
      }
    } catch (error: any) {
      // 检查是否是 Builder API Key 未配置的错误
      if (error.response?.data?.code === 2014 || error.message?.includes('Builder API Key 未配置')) {
        message.error({
          content: error.response?.data?.msg || error.message || 'Builder API Key 未配置，请前往系统设置页面配置',
          duration: 5,
        })
        // 延迟跳转，让用户看到错误消息
        setTimeout(() => {
          navigate('/system-settings/builder-api-key')
        }, 1500)
      } else {
        message.error('赎回失败: ' + (error.message || '未知错误'))
      }
    } finally {
      setRedeeming(false)
    }
  }
  
  // 订阅仓位推送
  const { connected: positionConnected } = useWebSocketSubscription<PositionPushMessage>(
    'position',
    (message) => {
      handlePositionPushMessage(message)
    }
  )
  
  // 更新连接状态（使用订阅的连接状态）
  useEffect(() => {
    setWsConnected(positionConnected)
  }, [positionConnected])
  
  /**
   * 处理仓位推送消息
   */
  const handlePositionPushMessage = (message: PositionPushMessage) => {
    if (message.type === 'FULL') {
      // 全量推送：直接替换（这是首次连接时的数据，完全以推送数据为准）
      setCurrentPositions(message.currentPositions || [])
      setHistoryPositions(message.historyPositions || [])
      setLoading(false)
      console.log('收到仓位全量推送:', {
        current: message.currentPositions?.length || 0,
        history: message.historyPositions?.length || 0
      })
    } else if (message.type === 'INCREMENTAL') {
      // 增量推送：合并数据（始终以推送数据为准）
      setCurrentPositions(prev => mergePositions(prev, message.currentPositions || [], message.removedPositionKeys || []))
      setHistoryPositions(prev => mergePositions(prev, message.historyPositions || [], message.removedPositionKeys || []))
      console.log('收到仓位增量推送:', {
        current: message.currentPositions?.length || 0,
        history: message.historyPositions?.length || 0,
        removed: message.removedPositionKeys?.length || 0
      })
    }
  }
  
  /**
   * 合并仓位数据
   * 新增的仓位插入到列表顶部，更新的仓位更新现有数据并保持位置，删除的仓位从列表中移除
   */
  const mergePositions = (
    prev: AccountPosition[],
    updates: AccountPosition[],
    removedKeys: string[]
  ): AccountPosition[] => {
    // 创建现有仓位的键集合，用于快速判断是新增还是更新
    const existingKeys = new Set(prev.map(pos => getPositionKey(pos)))
    
    // 区分新增和更新的仓位
    const newPositions: AccountPosition[] = []
    const updateMap = new Map<string, AccountPosition>()
    
    updates.forEach(update => {
      const key = getPositionKey(update)
      if (existingKeys.has(key)) {
        // 已存在的仓位，记录更新
        updateMap.set(key, update)
      } else {
        // 新增的仓位，插入到顶部
        newPositions.push(update)
      }
    })
    
    // 构建结果数组
    const result: AccountPosition[] = []
    
    // 1. 先添加新增的仓位（在顶部）
    result.push(...newPositions)
    
    // 2. 遍历原有仓位，应用更新或保持不变
    prev.forEach(pos => {
      const key = getPositionKey(pos)
      
      // 如果被删除，跳过
      if (removedKeys.includes(key)) {
        return
      }
      
      // 如果有更新，使用新数据；否则保持原数据
      if (updateMap.has(key)) {
        result.push(updateMap.get(key)!)
      } else {
        result.push(pos)
      }
    })
    
    return result
  }
  
  const fetchAccounts = async () => {
    setAccountsLoading(true)
    try {
      const response = await apiService.accounts.list()
      if (response.data.code === 0 && response.data.data) {
        setAccounts(response.data.data.list || [])
      } else {
        message.error(response.data.msg || '获取账户列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取账户列表失败')
    } finally {
      setAccountsLoading(false)
    }
  }
  
  // 已移除 fetchPositions 函数，完全依赖 WebSocket 推送更新数据
  
  // 根据筛选器选择对应的仓位列表
  const basePositions = useMemo(() => {
    return positionFilter === 'current' ? currentPositions : historyPositions
  }, [positionFilter, currentPositions, historyPositions])
  
  // 本地搜索和筛选过滤
  const filteredPositions = useMemo(() => {
    let filtered = basePositions
    
    // 1. 先按账户筛选
    if (selectedAccountId !== undefined) {
      filtered = filtered.filter(p => p.accountId === selectedAccountId)
    }
    
    // 2. 最后按关键词搜索
    if (searchKeyword.trim()) {
      const keyword = searchKeyword.trim().toLowerCase()
      filtered = filtered.filter(position => {
        // 搜索账户名
        if (position.accountName?.toLowerCase().includes(keyword)) {
          return true
        }
        // 搜索钱包地址
        if (position.walletAddress.toLowerCase().includes(keyword)) {
          return true
        }
        // 搜索市场标题
        if (position.marketTitle?.toLowerCase().includes(keyword)) {
          return true
        }
        // 搜索市场slug
        if (position.marketSlug?.toLowerCase().includes(keyword)) {
          return true
        }
        // 搜索市场ID
        if (position.marketId.toLowerCase().includes(keyword)) {
          return true
        }
        // 搜索方向（YES/NO）
        if (position.side.toLowerCase().includes(keyword)) {
          return true
        }
        return false
      })
    }
    
    return filtered
  }, [basePositions, searchKeyword, selectedAccountId])
  
  const getSideColor = (side: string) => {
    return side === 'YES' ? 'green' : 'red'
  }
  
  const formatNumber = (value: string | undefined, decimals: number = 2) => {
    if (!value) return '-'
    const num = parseFloat(value)
    if (isNaN(num)) return value
    return num.toFixed(decimals)
  }
  
  const formatPercent = (value: string | undefined) => {
    if (!value) return '-'
    const num = parseFloat(value)
    if (isNaN(num)) return value
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }

  // 统计当前筛选后的仓位合计：开仓价值、当前价值、盈亏、已实现盈亏
  const positionTotals = useMemo(() => {
    if (filteredPositions.length === 0) {
      return {
        totalInitialValue: 0,
        totalCurrentValue: 0,
        totalPnl: 0,
        totalRealizedPnl: 0
      }
    }

    let totalInitialValue = 0
    let totalCurrentValue = 0
    let totalPnl = 0
    let totalRealizedPnl = 0

    filteredPositions.forEach((pos) => {
      const initialValue = parseFloat(pos.initialValue || '0')
      const currentValue = parseFloat(pos.currentValue || '0')
      const pnl = parseFloat(pos.pnl || '0')
      const realizedPnl = parseFloat(pos.realizedPnl || '0')

      if (!isNaN(initialValue)) {
        totalInitialValue += initialValue
      }
      if (!isNaN(currentValue)) {
        totalCurrentValue += currentValue
      }
      if (!isNaN(pnl)) {
        totalPnl += pnl
      }
      if (!isNaN(realizedPnl)) {
        totalRealizedPnl += realizedPnl
      }
    })

    return {
      totalInitialValue,
      totalCurrentValue,
      totalPnl,
      totalRealizedPnl
    }
  }, [filteredPositions])

  // 切换卡片展开/折叠状态
  const toggleCard = (cardKey: string) => {
    setExpandedCards(prev => {
      const newSet = new Set(prev)
      if (newSet.has(cardKey)) {
        newSet.delete(cardKey)
      } else {
        newSet.add(cardKey)
      }
      return newSet
    })
  }

  // 处理卖出按钮点击
  const handleSellClick = async (position: AccountPosition) => {
    setSelectedPosition(position)
    setSellModalVisible(true)
    setOrderType('LIMIT')
    setSellQuantity('')
    setLimitPrice('')
    setSelectedPercent(null)  // 重置百分比选择
    form.resetFields()
    
    // 加载市场价格
    try {
      const response = await apiService.markets.getMarketPrice({ 
        marketId: position.marketId,
        outcomeIndex: position.outcomeIndex  // 传递结果索引，用于确定需要查询哪个 outcome 的价格
      })
      if (response.data.code === 0 && response.data.data) {
        setMarketPrice(response.data.data)
        // 默认使用当前价格作为限价
        if (response.data.data.currentPrice) {
          setLimitPrice(response.data.data.currentPrice)
          form.setFieldsValue({ limitPrice: response.data.data.currentPrice })
        }
      }
    } catch (error: any) {
      message.error('获取市场价格失败: ' + (error.message || '未知错误'))
    }
  }

  // 处理数量快捷按钮
  const handleQuantityQuickSelect = (percent: number) => {
    if (!selectedPosition) return
    // 记录选择的百分比（转为字符串，避免精度问题）
    setSelectedPercent(percent.toString())
    // 计算显示用的数量（用于预览，使用显示数量即可）
    const quantity = parseFloat(selectedPosition.quantity)
    const sellQty = (quantity * percent / 100).toFixed(4)
    setSellQuantity(sellQty)
    form.setFieldsValue({ quantity: sellQty })
    // 使用当前卖出价格计算收益
    const price = getCurrentSellPrice()
    if (price && price !== '0') {
      calculatePnl(sellQty, price)
    }
  }

  // 计算平仓收益
  const calculatePnl = (quantity: string, price: string) => {
    if (!selectedPosition || !quantity || !price) return { pnl: 0, percentPnl: 0 }
    
    const avgPrice = parseFloat(selectedPosition.avgPrice || '0')
    const sellPrice = parseFloat(price || '0')
    const qty = parseFloat(quantity || '0')
    
    // 验证数据有效性
    if (isNaN(avgPrice) || isNaN(sellPrice) || isNaN(qty) || avgPrice <= 0 || sellPrice <= 0 || qty <= 0) {
      return { pnl: 0, percentPnl: 0 }
    }
    
    // 计算收益：收益金额 = (卖出价格 - 平均买入价格) × 卖出数量
    const pnl = (sellPrice - avgPrice) * qty
    // 计算收益率：收益率 = (卖出价格 - 平均买入价格) / 平均买入价格 × 100%
    const percentPnl = ((sellPrice - avgPrice) / avgPrice) * 100
    
    return { pnl, percentPnl }
  }

  // 获取当前卖出价格（市价或限价）
  const getCurrentSellPrice = (): string => {
    if (orderType === 'MARKET') {
      // 市价订单（卖出）：使用当前价格
      return marketPrice?.currentPrice || selectedPosition?.currentPrice || '0'
    }
    return limitPrice || '0'
  }

  // 提交卖出订单
  const handleSellSubmit = async () => {
    if (!selectedPosition || submitting) return
    
    try {
      await form.validateFields()
      
      setSubmitting(true)
      
      const request: PositionSellRequest = {
        accountId: selectedPosition.accountId,
        marketId: selectedPosition.marketId,
        side: selectedPosition.side,
        outcomeIndex: selectedPosition.outcomeIndex,  // 传递 outcomeIndex
        orderType: orderType,
        // 如果选择了百分比，只传递百分比，不传 quantity
        // 如果手动输入，只传递 quantity，不传 percent
        ...(selectedPercent != null 
          ? { percent: selectedPercent } 
          : { quantity: sellQuantity }
        ),
        price: orderType === 'LIMIT' ? limitPrice : undefined
      }
      
      const response = await apiService.accounts.sellPosition(request)
      
      if (response.data.code === 0) {
        message.success('卖出订单创建成功')
        setSellModalVisible(false)
        // 重置表单
        setSellQuantity('')
        setLimitPrice('')
        setSelectedPercent(null)  // 重置百分比选择
        form.resetFields()
        // 仓位列表会通过WebSocket自动更新
      } else {
        message.error(response.data.msg || '创建卖出订单失败')
      }
    } catch (error: any) {
      if (error.errorFields) {
        // 表单验证错误
        return
      }
      message.error('创建卖出订单失败: ' + (error.message || '未知错误'))
    } finally {
      setSubmitting(false)
    }
  }

  // 实时计算收益（用于显示）
  const currentPnl = useMemo(() => {
    if (!selectedPosition || !sellQuantity) return { pnl: 0, percentPnl: 0 }
    const price = getCurrentSellPrice()
    if (!price || price === '0') return { pnl: 0, percentPnl: 0 }
    return calculatePnl(sellQuantity, price)
  }, [selectedPosition, sellQuantity, orderType, limitPrice, marketPrice])

  // 渲染卡片视图
  const renderCardView = () => {
    if (filteredPositions.length === 0) {
      return (
        <Empty 
          description="暂无仓位数据" 
          style={{ padding: '60px 0' }}
        />
      )
    }

    return (
      <Row gutter={[16, 16]}>
        {filteredPositions.map((position, index) => {
          const pnlNum = parseFloat(position.pnl || '0')
          const isProfit = pnlNum >= 0
          // 只有当前仓位才根据盈亏显示边框颜色
          const borderColor = positionFilter === 'current' 
            ? (isProfit ? 'rgba(82, 196, 26, 0.2)' : 'rgba(245, 34, 45, 0.2)')
            : 'rgba(0,0,0,0.06)'
          
          const cardKey = `${position.accountId}-${position.marketId}-${index}`
          const isExpanded = expandedCards.has(cardKey)
          // 移动端需要折叠功能，桌面端始终展开
          const shouldCollapse = isMobile && !isExpanded
          
          return (
            <Col 
              key={cardKey}
              xs={24} 
              sm={12} 
              lg={8} 
              xl={6}
            >
              <Card
                hoverable={!isMobile}
                onClick={() => isMobile && toggleCard(cardKey)}
                style={{
                  height: '100%',
                  borderRadius: '12px',
                  boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                  transition: 'all 0.3s ease',
                  border: `1px solid ${borderColor}`,
                  cursor: isMobile ? 'pointer' : 'default'
                }}
                bodyStyle={{ padding: '16px' }}
              >
                {/* 头部：市场图标和标题 */}
                <div style={{ marginBottom: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
                    {position.marketIcon && (
                      <img 
                        src={position.marketIcon} 
                        alt={position.marketTitle || 'Market'}
                        style={{ 
                          width: '48px', 
                          height: '48px', 
                          borderRadius: '8px',
                          objectFit: 'cover',
                          flexShrink: 0
                        }}
                        onError={(e) => {
                          e.currentTarget.style.display = 'none'
                        }}
                      />
                    )}
                    <div style={{ flex: 1, minWidth: 0 }}>
                      {position.marketTitle ? (
                        position.marketSlug ? (
                          <a 
                            href={`https://polymarket.com/event/${position.marketSlug}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            onClick={(e) => e.stopPropagation()}
                            style={{ 
                              fontWeight: 'bold', 
                              color: '#1890ff', 
                              textDecoration: 'none',
                              fontSize: '15px',
                              lineHeight: '1.4',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              display: '-webkit-box',
                              WebkitLineClamp: 2,
                              WebkitBoxOrient: 'vertical'
                            }}
                          >
                            {position.marketTitle}
                          </a>
                        ) : (
                          <div style={{ fontWeight: 'bold', fontSize: '15px', lineHeight: '1.4' }}>
                            {position.marketTitle}
                          </div>
                        )
                      ) : (
                        <div style={{ fontFamily: 'monospace', fontSize: '12px', color: '#999' }}>
                          {position.marketId.slice(0, 16)}...
                        </div>
                      )}
                      {position.marketSlug && (
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '4px' }}>
                          {position.marketSlug}
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                {/* 账户信息 */}
                <div style={{ marginBottom: '12px', paddingBottom: '12px', borderBottom: '1px solid #f0f0f0' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <div style={{ fontWeight: '500', fontSize: '14px', color: '#333' }}>
                        {position.accountName || `账户 ${position.accountId}`}
                      </div>
                      <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace', marginTop: '2px' }}>
                        {position.walletAddress.slice(0, 6)}...{position.walletAddress.slice(-4)}
                      </div>
                    </div>
                    <Tag color={getSideColor(position.side)} style={{ margin: 0 }}>
                      {position.side}
                    </Tag>
                  </div>
                </div>

                {/* 关键数据 */}
                <div style={{ marginBottom: '12px' }}>
                  {/* 移动端折叠时，显示盈亏（使用简单样式） */}
                  {shouldCollapse && positionFilter === 'current' && (
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                      <span style={{ fontSize: '13px', color: '#666' }}>盈亏</span>
                      <span style={{ 
                        fontSize: '13px', 
                        fontWeight: '500',
                        color: isProfit ? '#52c41a' : '#f5222d'
                      }}>
                        {pnlNum >= 0 ? '+' : ''}{formatUSDC(position.pnl)} USDC
                      </span>
                    </div>
                  )}
                  
                  {/* 展开时显示所有数据 */}
                  {!shouldCollapse && (
                    <>
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                        <span style={{ fontSize: '13px', color: '#666' }}>数量</span>
                        <span style={{ fontSize: '13px', fontWeight: '500' }}>
                          {formatNumber(position.quantity, 4)}
                        </span>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                        <span style={{ fontSize: '13px', color: '#666' }}>平均价格</span>
                        <span style={{ fontSize: '13px', fontWeight: '500' }}>
                          {formatNumber(position.avgPrice, 4)}
                        </span>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                        <span style={{ fontSize: '13px', color: '#666' }}>开仓价值</span>
                        <span style={{ fontSize: '13px', fontWeight: '500' }}>
                          {formatUSDC(position.initialValue)} USDC
                        </span>
                      </div>
                      {positionFilter === 'current' && position.currentPrice && (
                        <>
                          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                            <span style={{ fontSize: '13px', color: '#666' }}>当前价格</span>
                            <span style={{ fontSize: '13px', fontWeight: '500' }}>
                              {formatNumber(position.currentPrice, 4)}
                            </span>
                          </div>
                          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
                            <span style={{ fontSize: '13px', color: '#666' }}>当前价值</span>
                            <span style={{ fontSize: '13px', fontWeight: '600' }}>
                              {formatUSDC(position.currentValue)} USDC
                            </span>
                          </div>
                        </>
                      )}
                    </>
                  )}
                  
                  {/* 移动端展开/折叠指示器 */}
                  {isMobile && (
                    <div style={{ 
                      display: 'flex', 
                      justifyContent: 'center', 
                      alignItems: 'center',
                      marginTop: '8px',
                      paddingTop: '8px',
                      borderTop: '1px solid #f0f0f0'
                    }}>
                      {isExpanded ? (
                        <UpOutlined style={{ color: '#999', fontSize: '14px' }} />
                      ) : (
                        <DownOutlined style={{ color: '#999', fontSize: '14px' }} />
                      )}
                    </div>
                  )}
                </div>

                {/* 盈亏信息 - 突出显示（仅当前仓位显示，仅展开时显示） */}
                {positionFilter === 'current' && !shouldCollapse && (
                  <div style={{ 
                    marginBottom: '12px',
                    padding: '12px',
                    borderRadius: '8px',
                    background: isProfit ? 'rgba(82, 196, 26, 0.08)' : 'rgba(245, 34, 45, 0.08)',
                    border: `1px solid ${isProfit ? 'rgba(82, 196, 26, 0.2)' : 'rgba(245, 34, 45, 0.2)'}`
                  }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                      <span style={{ fontSize: '13px', color: '#666' }}>盈亏</span>
                      <span style={{ 
                        fontSize: '16px', 
                        fontWeight: 'bold',
                        color: isProfit ? '#52c41a' : '#f5222d'
                      }}>
                        {pnlNum >= 0 ? '+' : ''}{formatUSDC(position.pnl)} USDC
                      </span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                      <span style={{ 
                        fontSize: '14px',
                        color: isProfit ? '#52c41a' : '#f5222d',
                        fontWeight: '500'
                      }}>
                        {formatPercent(position.percentPnl)}
                      </span>
                    </div>
                    {position.realizedPnl && (
                      <div style={{ 
                        marginTop: '8px', 
                        paddingTop: '8px', 
                        borderTop: '1px solid rgba(0,0,0,0.06)',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center'
                      }}>
                        <span style={{ fontSize: '12px', color: '#999' }}>已实现盈亏</span>
                        <span style={{ 
                          fontSize: '13px',
                          color: parseFloat(position.realizedPnl) >= 0 ? '#52c41a' : '#f5222d',
                          fontWeight: '500'
                        }}>
                          {parseFloat(position.realizedPnl) >= 0 ? '+' : ''}{formatUSDC(position.realizedPnl)} USDC
                        </span>
                      </div>
                    )}
                  </div>
                )}

                {/* 操作按钮（移动端折叠时隐藏） */}
                {positionFilter === 'current' && !shouldCollapse && (
                  <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '8px' }}>
                    {!position.redeemable && (
                      <Button 
                        type="primary" 
                        danger 
                        size="small"
                        block={isMobile}
                        onClick={() => handleSellClick(position)}
                      >
                        卖出
                      </Button>
                    )}
                  </div>
                )}
              </Card>
            </Col>
          )
        })}
      </Row>
    )
  }
  
  // 根据仓位类型动态生成列（历史仓位不显示当前价格、当前价值、状态列）
  const columns = useMemo(() => {
    const baseColumns: any[] = [
    {
      title: '',
      key: 'icon',
      width: 50,
      render: (_: any, record: AccountPosition) => {
        if (!record.marketIcon) return null
        return (
          <img 
            src={record.marketIcon} 
            alt={record.marketTitle || 'Market'}
            style={{ 
              width: '32px', 
              height: '32px', 
              borderRadius: '4px',
              objectFit: 'cover'
            }}
            onError={(e) => {
              // 图片加载失败时隐藏
              e.currentTarget.style.display = 'none'
            }}
          />
        )
      },
      fixed: isMobile ? ('left' as const) : undefined
    },
    {
      title: '账户',
      dataIndex: 'accountName',
      key: 'accountName',
      render: (text: string | undefined, record: AccountPosition) => (
        <div>
          <div style={{ fontWeight: 'bold' }}>
            {text || `账户 ${record.accountId}`}
          </div>
          <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
            {record.walletAddress.slice(0, 6)}...{record.walletAddress.slice(-6)}
          </div>
        </div>
      ),
      fixed: isMobile ? ('left' as const) : undefined,
      width: isMobile ? 150 : 200
    },
    {
      title: '市场',
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      render: (text: string | undefined, record: AccountPosition) => {
        const url = record.marketSlug 
          ? `https://polymarket.com/event/${record.marketSlug}`
          : null
        
        const handleTitleClick = (e: React.MouseEvent) => {
          e.stopPropagation()
          if (url) {
            window.open(url, '_blank', 'noopener,noreferrer')
          }
        }
        
        return (
          <div>
            {text ? (
              <div>
                {url ? (
                  <a 
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={handleTitleClick}
                    style={{ fontWeight: 'bold', color: '#1890ff', textDecoration: 'none', cursor: 'pointer' }}
                  >
                    {text}
                  </a>
                ) : (
                  <div style={{ fontWeight: 'bold' }}>{text}</div>
                )}
              </div>
            ) : (
              <div style={{ fontFamily: 'monospace', fontSize: '12px' }}>
                {record.marketId.slice(0, 10)}...
              </div>
            )}
            {record.marketSlug && (
              <div style={{ fontSize: '12px', color: '#999' }}>{record.marketSlug}</div>
            )}
          </div>
        )
      },
      width: isMobile ? 200 : 250
    },
    {
      title: '方向',
      dataIndex: 'side',
      key: 'side',
      render: (side: string) => (
        <Tag color={getSideColor(side)}>{side}</Tag>
      ),
      width: 80
    },
    {
      title: '数量',
      dataIndex: 'quantity',
      key: 'quantity',
      render: (quantity: string) => formatNumber(quantity, 4),
      align: 'right' as const,
      width: 100
    },
    {
      title: '平均价格',
      dataIndex: 'avgPrice',
      key: 'avgPrice',
      render: (price: string) => formatNumber(price, 4),
      align: 'right' as const,
      width: 120
    },
    {
      title: '开仓价值',
      dataIndex: 'initialValue',
      key: 'initialValue',
      render: (value: string) => (
        <span>
          {formatUSDC(value)} USDC
        </span>
      ),
      align: 'right' as const,
      width: 120
    },
    ]
    
    // 只有当前仓位才显示当前价格和当前价值列
    if (positionFilter === 'current') {
      baseColumns.push(
        {
          title: '当前价格',
          dataIndex: 'currentPrice',
          key: 'currentPrice',
          render: (price: string) => formatNumber(price, 4),
          align: 'right' as const,
          width: 120
        },
        {
          title: '当前价值',
          dataIndex: 'currentValue',
          key: 'currentValue',
          render: (value: string) => (
            <span style={{ fontWeight: 'bold' }}>
              {formatUSDC(value)} USDC
            </span>
          ),
          align: 'right' as const,
          width: 120,
          sorter: (a: AccountPosition, b: AccountPosition) => {
            const valA = parseFloat(a.currentValue || '0')
            const valB = parseFloat(b.currentValue || '0')
            return valA - valB
          },
          defaultSortOrder: 'descend' as const
        }
      )
    }
    
    // 只有当前仓位才显示盈亏和已实现盈亏列
    if (positionFilter === 'current') {
    baseColumns.push(
      {
        title: '盈亏',
        dataIndex: 'pnl',
        key: 'pnl',
        render: (pnl: string, record: AccountPosition) => {
          const pnlNum = parseFloat(pnl || '0')
          const percentPnl = parseFloat(record.percentPnl || '0')
          return (
            <div>
              <div style={{ 
                color: pnlNum >= 0 ? '#3f8600' : '#cf1322',
                fontWeight: 'bold'
              }}>
                {pnlNum >= 0 ? '+' : ''}{formatUSDC(pnl)} USDC
              </div>
              <div style={{ 
                fontSize: '12px',
                color: percentPnl >= 0 ? '#3f8600' : '#cf1322'
              }}>
                {formatPercent(record.percentPnl)}
              </div>
            </div>
          )
        },
        align: 'right' as const,
        width: 150,
        sorter: (a: AccountPosition, b: AccountPosition) => {
          const pnlA = parseFloat(a.pnl || '0')
          const pnlB = parseFloat(b.pnl || '0')
          return pnlA - pnlB
        }
      },
      {
        title: '已实现盈亏',
        dataIndex: 'realizedPnl',
        key: 'realizedPnl',
        render: (realizedPnl: string | undefined, record: AccountPosition) => {
          if (!realizedPnl) return '-'
          const pnlNum = parseFloat(realizedPnl)
          const percentPnl = parseFloat(record.percentRealizedPnl || '0')
          return (
            <div>
              <div style={{ 
                color: pnlNum >= 0 ? '#3f8600' : '#cf1322',
                fontWeight: 'bold'
              }}>
                {pnlNum >= 0 ? '+' : ''}{formatUSDC(realizedPnl)} USDC
              </div>
              {record.percentRealizedPnl && (
                <div style={{ 
                  fontSize: '12px',
                  color: percentPnl >= 0 ? '#3f8600' : '#cf1322'
                }}>
                  {formatPercent(record.percentRealizedPnl)}
                </div>
              )}
            </div>
          )
        },
        align: 'right' as const,
        width: 150
      }
    )
    }
    
    // 只有当前仓位才显示操作列
    if (positionFilter === 'current') {
      baseColumns.push({
        title: '操作',
        key: 'action',
        render: (_: any, record: AccountPosition) => (
          <Space size="small">
            {!record.redeemable && (
              <Button 
                type="primary" 
                danger 
                size="small"
                onClick={() => handleSellClick(record)}
              >
                卖出
              </Button>
            )}
          </Space>
        ),
        width: 150,
        fixed: isMobile ? ('right' as const) : undefined
      })
    }
    
    return baseColumns
  }, [positionFilter, isMobile])
  
  // 统计当前和历史仓位数量（根据账户筛选）
  const filteredCurrentPositions = useMemo(() => {
    if (selectedAccountId === undefined) return currentPositions
    return currentPositions.filter(p => p.accountId === selectedAccountId)
  }, [currentPositions, selectedAccountId])
  
  const filteredHistoryPositions = useMemo(() => {
    if (selectedAccountId === undefined) return historyPositions
    return historyPositions.filter(p => p.accountId === selectedAccountId)
  }, [historyPositions, selectedAccountId])
  
  const currentCount = filteredCurrentPositions.length
  const historicalCount = filteredHistoryPositions.length
      
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px', marginBottom: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <h2 style={{ margin: 0 }}>仓位管理</h2>
            {/* WebSocket 连接状态指示器 */}
            <Tag 
              color={wsConnected ? 'green' : 'orange'} 
              style={{ margin: 0 }}
            >
              <span style={{ 
                display: 'inline-block', 
                width: '8px', 
                height: '8px', 
                borderRadius: '50%', 
                backgroundColor: wsConnected ? '#52c41a' : '#fa8c16', 
                marginRight: '6px', 
                animation: wsConnected ? 'pulse 2s infinite' : 'pulse 1s infinite'
              }}></span>
              {wsConnected ? '实时更新' : '连接中...'}
            </Tag>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flex: isMobile ? '1 1 100%' : '0 0 auto', flexWrap: 'wrap' }}>
            <Input
              placeholder="搜索账户、市场、方向..."
              prefix={<SearchOutlined />}
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              allowClear
              style={{ width: isMobile ? '100%' : 300 }}
            />
            {!isMobile && (
              <Button.Group>
                <Button 
                  type={viewMode === 'list' ? 'primary' : 'default'}
                  icon={<UnorderedListOutlined />}
                  onClick={() => setViewMode('list')}
                  title="列表视图"
                />
                <Button 
                  type={viewMode === 'card' ? 'primary' : 'default'}
                  icon={<AppstoreOutlined />}
                  onClick={() => setViewMode('card')}
                  title="卡片视图"
                />
              </Button.Group>
            )}
            <span style={{ color: '#999', fontSize: '14px', whiteSpace: 'nowrap' }}>
              {searchKeyword || selectedAccountId !== undefined
                ? `找到 ${filteredPositions.length} / ${basePositions.length} 个仓位` 
                : `共 ${basePositions.length} 个仓位`}
            </span>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flexWrap: 'wrap' }}>
          <Select
            placeholder="选择账户"
            value={selectedAccountId ?? null}
            onChange={(value) => setSelectedAccountId(value ?? undefined)}
            style={{ width: isMobile ? '100%' : 200 }}
            loading={accountsLoading}
            options={[
              { value: null, label: '全部账户' },
              ...accounts
                .sort((a, b) => {
                  const nameA = (a.accountName || `账户 ${a.id}`).toLowerCase()
                  const nameB = (b.accountName || `账户 ${b.id}`).toLowerCase()
                  return nameA.localeCompare(nameB, 'zh-CN')
                })
                .map(account => ({
                value: account.id,
                label: account.accountName || `账户 ${account.id}`
              }))
            ]}
          />
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
            <div style={{
              background: '#f5f5f5',
              padding: '4px',
              borderRadius: '8px',
              display: 'inline-flex',
              gap: '4px'
            }}>
            <Radio.Group 
              value={positionFilter} 
              onChange={(e) => setPositionFilter(e.target.value)}
              size={isMobile ? 'small' : 'middle'}
                style={{ display: 'flex', gap: '4px' }}
              >
                <Radio.Button 
                  value="current"
                  style={{
                    border: 'none',
                    borderRadius: '6px',
                    padding: '8px 16px',
                    height: 'auto',
                    lineHeight: '1.5',
                    transition: 'all 0.3s ease',
                    background: positionFilter === 'current' ? '#1890ff' : 'transparent',
                    color: positionFilter === 'current' ? '#fff' : '#666',
                    fontWeight: positionFilter === 'current' ? '500' : 'normal',
                    boxShadow: positionFilter === 'current' ? '0 2px 4px rgba(24, 144, 255, 0.2)' : 'none'
                  }}
                >
                  <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span>当前仓位</span>
                    <Tag 
                      color={positionFilter === 'current' ? 'default' : 'blue'} 
                      style={{ 
                        margin: 0,
                        borderRadius: '10px',
                        fontSize: '12px',
                        lineHeight: '20px',
                        padding: '0 8px',
                        background: positionFilter === 'current' ? 'rgba(255, 255, 255, 0.3)' : undefined,
                        color: positionFilter === 'current' ? '#fff' : undefined,
                        border: positionFilter === 'current' ? 'none' : undefined
                      }}
                    >
                      {currentCount}
                    </Tag>
                  </span>
              </Radio.Button>
                <Radio.Button 
                  value="historical"
                  style={{
                    border: 'none',
                    borderRadius: '6px',
                    padding: '8px 16px',
                    height: 'auto',
                    lineHeight: '1.5',
                    transition: 'all 0.3s ease',
                    background: positionFilter === 'historical' ? '#1890ff' : 'transparent',
                    color: positionFilter === 'historical' ? '#fff' : '#666',
                    fontWeight: positionFilter === 'historical' ? '500' : 'normal',
                    boxShadow: positionFilter === 'historical' ? '0 2px 4px rgba(24, 144, 255, 0.2)' : 'none'
                  }}
                >
                  <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span>历史仓位</span>
                    <Tag 
                      color={positionFilter === 'historical' ? 'default' : 'default'} 
                      style={{ 
                        margin: 0,
                        borderRadius: '10px',
                        fontSize: '12px',
                        lineHeight: '20px',
                        padding: '0 8px',
                        background: positionFilter === 'historical' ? 'rgba(255, 255, 255, 0.3)' : undefined,
                        color: positionFilter === 'historical' ? '#fff' : undefined,
                        border: positionFilter === 'historical' ? 'none' : undefined
                      }}
                    >
                      {historicalCount}
                    </Tag>
                  </span>
              </Radio.Button>
            </Radio.Group>
            </div>
            {redeemableSummary && redeemableSummary.totalCount > 0 && (
              <Button
                type="primary"
                onClick={handleRedeemClick}
                loading={loadingRedeemableSummary}
                style={{
                  background: '#52c41a',
                  borderColor: '#52c41a'
                }}
              >
                赎回 ({redeemableSummary.totalCount}个, {formatUSDC(redeemableSummary.totalValue)} USDC)
              </Button>
            )}
          </div>
        </div>
        {/* 合计信息：开仓价值、当前价值、盈亏、已实现盈亏（基于当前筛选后的仓位） */}
        {filteredPositions.length > 0 && (
          <div
            style={{
              marginTop: '12px',
              padding: '10px 16px',
              borderRadius: '8px',
              background: '#f5f5f5',
              display: 'flex',
              flexWrap: 'wrap',
              gap: '16px',
              fontSize: '13px',
              color: '#555'
            }}
          >
            <span>
              开仓价值合计：{' '}
              <span style={{ fontWeight: 600 }}>
                {formatUSDC(positionTotals.totalInitialValue.toString())} USDC
              </span>
            </span>
            <span>
              当前价值合计：{' '}
              <span style={{ fontWeight: 600 }}>
                {positionFilter === 'current'
                  ? `${formatUSDC(positionTotals.totalCurrentValue.toString())} USDC`
                  : '-'}
              </span>
            </span>
            <span>
              盈亏合计：{' '}
              <span
                style={{
                  fontWeight: 600,
                  color: positionTotals.totalPnl >= 0 ? '#3f8600' : '#cf1322'
                }}
              >
                {positionTotals.totalPnl >= 0 ? '+' : ''}
                {formatUSDC(positionTotals.totalPnl.toString())} USDC
              </span>
            </span>
            <span>
              已实现盈亏合计：{' '}
              <span
                style={{
                  fontWeight: 600,
                  color: positionTotals.totalRealizedPnl >= 0 ? '#3f8600' : '#cf1322'
                }}
              >
                {positionTotals.totalRealizedPnl >= 0 ? '+' : ''}
                {formatUSDC(positionTotals.totalRealizedPnl.toString())} USDC
              </span>
            </span>
          </div>
        )}
      </div>
      
      {(isMobile || viewMode === 'card') ? (
        <Card loading={loading}>
          {renderCardView()}
          {filteredPositions.length > 0 && (
            <div style={{ 
              marginTop: '24px', 
              textAlign: 'center',
              color: '#999',
              fontSize: '14px'
            }}>
              共 {filteredPositions.length} 个仓位{searchKeyword ? `（已过滤）` : ''}
            </div>
          )}
        </Card>
      ) : (
      <Card>
        <Table
          dataSource={filteredPositions}
          columns={columns}
          rowKey={(record, index) => `${record.accountId}-${record.marketId}-${index}`}
          loading={loading}
          pagination={{
            pageSize: 20,
            showSizeChanger: !isMobile,
            showTotal: (total) => `共 ${total} 个仓位${searchKeyword ? `（已过滤）` : ''}`
          }}
          scroll={isMobile ? { x: 1500 } : undefined}
        />
      </Card>
      )}
      
      {/* 出售模态框 */}
      <Modal
        title={`出售仓位 - ${selectedPosition?.marketTitle || selectedPosition?.marketId || ''}`}
        open={sellModalVisible}
        onCancel={() => {
          if (!submitting) {
            setSellModalVisible(false)
          }
        }}
        onOk={handleSellSubmit}
        okText="确认卖出"
        cancelText="取消"
        width={isMobile ? '90%' : 600}
        destroyOnClose
        confirmLoading={submitting}
        maskClosable={!submitting}
      >
        {selectedPosition && (
          <Form form={form} layout="vertical">
            <div style={{ marginBottom: '16px', padding: '12px', background: '#f5f5f5', borderRadius: '8px' }}>
              <div style={{ marginBottom: '8px' }}>
                <span style={{ color: '#666' }}>账户: </span>
                <span style={{ fontWeight: '500' }}>{selectedPosition.accountName || `账户 ${selectedPosition.accountId}`}</span>
              </div>
              <div style={{ marginBottom: '8px' }}>
                <span style={{ color: '#666' }}>方向: </span>
                <Tag color={getSideColor(selectedPosition.side)}>{selectedPosition.side}</Tag>
              </div>
              <div style={{ marginBottom: '8px' }}>
                <span style={{ color: '#666' }}>当前持仓: </span>
                <span style={{ fontWeight: '500' }}>{formatNumber(selectedPosition.quantity, 4)}</span>
              </div>
              <div style={{ marginBottom: '8px' }}>
                <span style={{ color: '#666' }}>平均价格: </span>
                <span style={{ fontWeight: '500' }}>{formatNumber(selectedPosition.avgPrice, 4)}</span>
              </div>
              {selectedPosition.currentPrice && (
                <div>
                  <span style={{ color: '#666' }}>当前价格: </span>
                  <span style={{ fontWeight: '500' }}>{formatNumber(selectedPosition.currentPrice, 4)}</span>
                </div>
              )}
            </div>
            
            <Form.Item label="订单类型" required>
              <Radio.Group 
                value={orderType} 
                onChange={(e) => {
                  setOrderType(e.target.value)
                  // 切换订单类型时重新计算收益
                  if (sellQuantity) {
                    const price = e.target.value === 'MARKET' 
                      ? (marketPrice?.currentPrice || selectedPosition?.currentPrice || '0')
                      : limitPrice || '0'
                    calculatePnl(sellQuantity, price)
                  }
                }}
              >
                <Radio value="MARKET">市价出售</Radio>
                <Radio value="LIMIT">限价出售</Radio>
              </Radio.Group>
            </Form.Item>
            
            <Form.Item 
              label="卖出数量" 
              name="quantity"
              rules={[
                { required: true, message: '请输入卖出数量' },
                { 
                  validator: (_, value) => {
                    if (!value || parseFloat(value) <= 0) {
                      return Promise.reject('卖出数量必须大于0')
                    }
                    if (parseFloat(value) > parseFloat(selectedPosition.quantity)) {
                      return Promise.reject('卖出数量不能超过持仓数量')
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <Input
                value={sellQuantity}
                onChange={(e) => {
                  const newQuantity = e.target.value
                  setSellQuantity(newQuantity)
                  // 用户手动输入时，清除百分比选择
                  setSelectedPercent(null)
                  if (newQuantity) {
                    const price = getCurrentSellPrice()
                    calculatePnl(newQuantity, price)
                  }
                }}
                placeholder="请输入卖出数量"
                suffix={
                  <Space size="small">
                    <Button size="small" onClick={() => handleQuantityQuickSelect(20)}>20%</Button>
                    <Button size="small" onClick={() => handleQuantityQuickSelect(50)}>50%</Button>
                    <Button size="small" onClick={() => handleQuantityQuickSelect(80)}>80%</Button>
                    <Button size="small" onClick={() => handleQuantityQuickSelect(100)}>100%</Button>
                  </Space>
                }
              />
            </Form.Item>
            
            {orderType === 'LIMIT' && (
              <Form.Item 
                label="限价价格" 
                name="limitPrice"
                rules={[
                  { required: true, message: '请输入限价价格' },
                  { 
                    validator: (_, value) => {
                      if (!value || parseFloat(value) <= 0) {
                        return Promise.reject('价格必须大于0')
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <Input
                  value={limitPrice}
                  onChange={(e) => {
                    const newPrice = e.target.value
                    setLimitPrice(newPrice)
                    if (sellQuantity && newPrice) {
                      calculatePnl(sellQuantity, newPrice)
                    }
                  }}
                  placeholder="请输入限价价格"
                />
                {marketPrice?.currentPrice && (
                  <div style={{ marginTop: '4px', fontSize: '12px', color: '#999' }}>
                    参考价格（卖出参考）: {formatNumber(marketPrice.currentPrice, 4)}
                  </div>
                )}
              </Form.Item>
            )}
            
            {orderType === 'MARKET' && (
              <div style={{ marginBottom: '16px', padding: '12px', background: '#f0f7ff', borderRadius: '8px' }}>
                <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>市价参考（卖出）</div>
                <div style={{ fontSize: '14px' }}>
                  {marketPrice?.currentPrice ? (
                    <>当前价格: <span style={{ fontWeight: '500' }}>{formatNumber(marketPrice.currentPrice, 4)}</span></>
                  ) : selectedPosition?.currentPrice ? (
                    <>当前价格: <span style={{ fontWeight: '500' }}>{formatNumber(selectedPosition.currentPrice, 4)}</span></>
                  ) : (
                    <span style={{ color: '#999' }}>暂无价格数据</span>
                  )}
                </div>
              </div>
            )}
            
            {/* 预计平仓收益 */}
            {sellQuantity && (
              <div style={{ 
                marginTop: '16px', 
                padding: '16px', 
                background: currentPnl.pnl >= 0 ? 'rgba(82, 196, 26, 0.08)' : 'rgba(245, 34, 45, 0.08)',
                border: `1px solid ${currentPnl.pnl >= 0 ? 'rgba(82, 196, 26, 0.2)' : 'rgba(245, 34, 45, 0.2)'}`,
                borderRadius: '8px'
              }}>
                <div style={{ fontSize: '13px', color: '#666', marginBottom: '8px' }}>预计平仓收益</div>
                <div style={{ 
                  fontSize: '20px', 
                  fontWeight: 'bold',
                  color: currentPnl.pnl >= 0 ? '#52c41a' : '#f5222d',
                  marginBottom: '4px'
                }}>
                  {currentPnl.pnl >= 0 ? '+' : ''}{formatUSDC(currentPnl.pnl)} USDC
                </div>
                <div style={{ 
                  fontSize: '14px',
                  color: currentPnl.percentPnl >= 0 ? '#52c41a' : '#f5222d',
                  fontWeight: '500'
                }}>
                  {currentPnl.percentPnl >= 0 ? '+' : ''}{currentPnl.percentPnl.toFixed(2)}%
                </div>
              </div>
            )}
          </Form>
        )}
      </Modal>
      
      {/* 赎回模态框 */}
      <Modal
        title="赎回仓位详情"
        open={redeemModalVisible}
        onCancel={() => {
          if (!redeeming) {
            setRedeemModalVisible(false)
          }
        }}
        onOk={handleRedeemSubmit}
        okText="确认赎回"
        cancelText="取消"
        width={isMobile ? '90%' : 800}
        destroyOnClose
        confirmLoading={redeeming}
        maskClosable={!redeeming}
      >
        {redeemableSummary && redeemableSummary.positions.length > 0 ? (
          <div>
            <Descriptions bordered column={1} size="small" style={{ marginBottom: '16px' }}>
              <Descriptions.Item label="可赎回仓位数量">
                <Tag color="green">{redeemableSummary.totalCount} 个</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="可赎回总价值">
                <span style={{ fontSize: '18px', fontWeight: 'bold', color: '#52c41a' }}>
                  {formatUSDC(redeemableSummary.totalValue)} USDC
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="涉及账户">
                <Tag color="blue">
                  {new Set(redeemableSummary.positions.map(p => p.accountId)).size} 个账户
                </Tag>
              </Descriptions.Item>
            </Descriptions>
            
            <div style={{ marginTop: '16px' }}>
              <div style={{ marginBottom: '8px', fontWeight: '500' }}>赎回仓位列表：</div>
              <Table
                dataSource={redeemableSummary.positions}
                rowKey={(record, index) => `${record.marketId}-${record.outcomeIndex}-${index}`}
                pagination={false}
                size="small"
                scroll={{ y: 300 }}
                columns={[
                  {
                    title: '账户',
                    dataIndex: 'accountName',
                    key: 'account',
                    render: (text, record) => (
                      <span>
                        {text || `账户 ${record.accountId}`}
                      </span>
                    ),
                    width: 150
                  },
                  {
                    title: '市场',
                    dataIndex: 'marketTitle',
                    key: 'marketTitle',
                    render: (text, record) => text || record.marketId.substring(0, 10) + '...',
                    width: 200
                  },
                  {
                    title: '方向',
                    dataIndex: 'side',
                    key: 'side',
                    render: (side) => <Tag color={getSideColor(side)}>{side}</Tag>,
                    width: 80
                  },
                  {
                    title: '数量',
                    dataIndex: 'quantity',
                    key: 'quantity',
                    align: 'right' as const,
                    render: (value) => formatNumber(value, 4),
                    width: 120
                  },
                  {
                    title: '价值 (USDC)',
                    dataIndex: 'value',
                    key: 'value',
                    align: 'right' as const,
                    render: (value) => (
                      <span style={{ fontWeight: '500', color: '#52c41a' }}>
                        {formatNumber(value, 2)}
                      </span>
                    ),
                    width: 120
                  }
                ]}
              />
            </div>
            
            <div style={{ 
              marginTop: '16px', 
              padding: '12px', 
              background: '#f0f9ff', 
              borderRadius: '8px',
              border: '1px solid #bae7ff'
            }}>
              <div style={{ color: '#666', fontSize: '12px', lineHeight: '1.8' }}>
                <div>💡 <strong>提示：</strong></div>
                <div>• 赎回将按 1:1 比例将获胜仓位换回 USDC</div>
                <div>• 同一市场的多个仓位将批量赎回，节省 Gas 费用</div>
                <div>• 赎回操作需要发送链上交易，请确保账户有足够的 POL 支付 Gas</div>
                <div>• 赎回成功后，仓位将从当前仓位列表中移除</div>
              </div>
            </div>
          </div>
        ) : (
          <div style={{ textAlign: 'center', padding: '40px' }}>
            <Empty description="没有可赎回的仓位" />
          </div>
        )}
      </Modal>
    </div>
  )
}

export default PositionList

