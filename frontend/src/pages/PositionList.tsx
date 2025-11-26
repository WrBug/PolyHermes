import { useEffect, useState, useMemo } from 'react'
import { Card, Table, Tag, message, Space, Input, Radio, Select, Button, Row, Col, Empty } from 'antd'
import { SearchOutlined, AppstoreOutlined, UnorderedListOutlined, UpOutlined, DownOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { AccountPosition, Account, PositionPushMessage } from '../types'
import { getPositionKey } from '../types'
import { useMediaQuery } from 'react-responsive'
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import { wsManager } from '../services/websocket'

type PositionFilter = 'current' | 'historical'
type ViewMode = 'card' | 'list'

const PositionList: React.FC = () => {
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
  const [wsConnected, setWsConnected] = useState(false)
  
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
                        {pnlNum >= 0 ? '+' : ''}{formatNumber(position.pnl, 2)} USDC
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
                              {formatNumber(position.currentValue, 2)} USDC
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
                        {pnlNum >= 0 ? '+' : ''}{formatNumber(position.pnl, 2)} USDC
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
                          {parseFloat(position.realizedPnl) >= 0 ? '+' : ''}{formatNumber(position.realizedPnl, 2)} USDC
                        </span>
                      </div>
                    )}
                  </div>
                )}

                {/* 状态标签（移动端折叠时隐藏） */}
                {positionFilter === 'current' && !shouldCollapse && (position.redeemable || position.mergeable) && (
                  <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                    {position.redeemable && (
                      <Tag color="green" style={{ margin: 0 }}>可赎回</Tag>
                    )}
                    {position.mergeable && (
                      <Tag color="blue" style={{ margin: 0 }}>可合并</Tag>
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
              {formatNumber(value, 2)} USDC
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
                  {pnlNum >= 0 ? '+' : ''}{formatNumber(pnl, 2)} USDC
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
                  {pnlNum >= 0 ? '+' : ''}{formatNumber(realizedPnl, 2)} USDC
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
    
    // 只有当前仓位才显示状态列
    if (positionFilter === 'current') {
      baseColumns.push({
        title: '状态',
        key: 'status',
        render: (_: any, record: AccountPosition) => (
          <Space size="small">
            {record.redeemable && <Tag color="green">可赎回</Tag>}
            {record.mergeable && <Tag color="blue">可合并</Tag>}
          </Space>
        ),
        width: 120
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
        </div>
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
    </div>
  )
}

export default PositionList

