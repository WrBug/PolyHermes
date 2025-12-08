import { useEffect, useState } from 'react'
import { Table, Tag, Select, Input, Button, Card, Divider, Spin } from 'antd'
import { apiService } from '../../services/api'
import { formatUSDC } from '../../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { BuyOrderInfo, OrderTrackingRequest, OrderTrackingListResponse } from '../../types'

const { Option } = Select

interface BuyOrdersTabProps {
  copyTradingId: string
}

const BuyOrdersTab: React.FC<BuyOrdersTabProps> = ({ copyTradingId }) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [orders, setOrders] = useState<BuyOrderInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(20)
  const [filters, setFilters] = useState<{
    marketId?: string
    side?: string
    status?: string
  }>({})
  
  useEffect(() => {
    if (copyTradingId) {
      fetchOrders()
    }
  }, [copyTradingId, page, limit, filters])
  
  const fetchOrders = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const request: OrderTrackingRequest = {
        copyTradingId: parseInt(copyTradingId),
        type: 'buy',
        page,
        limit,
        ...filters
      }
      
      const response = await apiService.orderTracking.list(request)
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data as OrderTrackingListResponse
        setOrders((data.list || []) as BuyOrderInfo[])
        setTotal(data.total || 0)
      }
    } catch (error: any) {
      console.error('获取买入订单列表失败:', error)
    } finally {
      setLoading(false)
    }
  }
  
  const getStatusTag = (status: string) => {
    const statusMap: Record<string, { color: string; text: string }> = {
      filled: { color: 'processing', text: t('copyTradingOrders.statusFilled') || '已完成' },
      partially_matched: { color: 'warning', text: t('copyTradingOrders.statusPartiallySold') || '部分成交' },
      fully_matched: { color: 'success', text: t('copyTradingOrders.statusFullySold') || '全部成交' }
    }
    const config = statusMap[status] || { color: 'default', text: status }
    return <Tag color={config.color}>{config.text}</Tag>
  }
  
  const columns = [
    {
      title: t('copyTradingOrders.orderId') || '订单ID',
      dataIndex: 'orderId',
      key: 'orderId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('copyTradingOrders.leaderTradeId') || 'Leader 交易ID',
      dataIndex: 'leaderTradeId',
      key: 'leaderTradeId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('copyTradingOrders.market') || '市场',
      dataIndex: 'marketId',
      key: 'marketId',
      width: isMobile ? 100 : 150,
      render: (text: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? `${text.slice(0, 6)}...${text.slice(-4)}`
            : `${text.slice(0, 8)}...${text.slice(-6)}`
          }
        </span>
      )
    },
    {
      title: t('copyTradingOrders.side') || '方向',
      dataIndex: 'side',
      key: 'side',
      width: isMobile ? 60 : 80,
      render: (side: string) => {
        const displaySide = side === '0' ? 'YES' : side === '1' ? 'NO' : side
        return <Tag style={{ fontSize: isMobile ? 11 : 12 }}>{displaySide}</Tag>
      }
    },
    {
      title: t('copyTradingOrders.buyQuantity') || '买入数量',
      dataIndex: 'quantity',
      key: 'quantity',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.buyPrice') || '买入价格',
      dataIndex: 'price',
      key: 'price',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.buyAmount') || '买入金额',
      key: 'amount',
      width: isMobile ? 100 : 120,
      render: (_: any, record: BuyOrderInfo) => {
        const amount = (parseFloat(record.quantity) * parseFloat(record.price)).toString()
        return (
          <span style={{ fontSize: isMobile ? 12 : 14 }}>
            {isMobile ? formatUSDC(amount) : `${formatUSDC(amount)} USDC`}
          </span>
        )
      }
    },
    {
      title: t('copyTradingOrders.matchedQuantity') || '已匹配',
      dataIndex: 'matchedQuantity',
      key: 'matchedQuantity',
      width: isMobile ? 70 : 90,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.remainingQuantity') || '剩余',
      dataIndex: 'remainingQuantity',
      key: 'remainingQuantity',
      width: isMobile ? 70 : 90,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.sellStatus') || '卖出状态',
      dataIndex: 'status',
      key: 'status',
      width: isMobile ? 80 : 100,
      render: (status: string) => getStatusTag(status)
    },
    {
      title: t('copyTradingOrders.createdAt') || '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 160,
      render: (timestamp: number) => (
        <span style={{ fontSize: isMobile ? 11 : 12 }}>
          {isMobile 
            ? new Date(timestamp).toLocaleDateString('zh-CN')
            : new Date(timestamp).toLocaleString('zh-CN')
          }
        </span>
      )
    }
  ]
  
  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <Input
          placeholder={t('copyTradingOrders.filterMarketId') || '筛选市场ID'}
          allowClear
          style={{ width: isMobile ? '100%' : 200 }}
          value={filters.marketId}
          onChange={(e) => setFilters({ ...filters, marketId: e.target.value || undefined })}
        />
        
        <Select
          placeholder={t('copyTradingOrders.filterSide') || '筛选方向'}
          allowClear
          style={{ width: isMobile ? '100%' : 150 }}
          value={filters.side}
          onChange={(value) => setFilters({ ...filters, side: value || undefined })}
        >
          <Option value="0">YES</Option>
          <Option value="1">NO</Option>
          <Option value="YES">YES</Option>
          <Option value="NO">NO</Option>
        </Select>
        
        <Select
          placeholder={t('copyTradingOrders.filterStatus') || '筛选状态'}
          allowClear
          style={{ width: isMobile ? '100%' : 150 }}
          value={filters.status}
          onChange={(value) => setFilters({ ...filters, status: value || undefined })}
        >
          <Option value="filled">{t('copyTradingOrders.statusFilled') || '已完成'}</Option>
          <Option value="partially_matched">{t('copyTradingOrders.statusPartiallySold') || '部分成交'}</Option>
          <Option value="fully_matched">{t('copyTradingOrders.statusFullySold') || '全部成交'}</Option>
        </Select>
        
        <Button onClick={fetchOrders}>{t('common.search') || '查询'}</Button>
      </div>
      
      {isMobile ? (
        <div>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px' }}>
              <Spin size="large" />
            </div>
          ) : orders.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
              {t('copyTradingOrders.noBuyOrders') || '暂无买入订单'}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {orders.map((order) => {
                const date = new Date(order.createdAt)
                const formattedDate = date.toLocaleString('zh-CN', {
                  year: 'numeric',
                  month: '2-digit',
                  day: '2-digit',
                  hour: '2-digit',
                  minute: '2-digit'
                })
                const amount = (parseFloat(order.quantity) * parseFloat(order.price)).toString()
                const displaySide = order.side === '0' ? 'YES' : order.side === '1' ? 'NO' : order.side
                
                return (
                  <Card
                    key={order.orderId}
                    style={{
                      borderRadius: '12px',
                      boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                      border: '1px solid #e8e8e8'
                    }}
                    bodyStyle={{ padding: '16px' }}
                  >
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ 
                        fontSize: '14px', 
                        fontWeight: 'bold', 
                        marginBottom: '8px',
                        fontFamily: 'monospace'
                      }}>
                        {order.orderId.slice(0, 8)}...{order.orderId.slice(-6)}
                      </div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                        <Tag>{displaySide}</Tag>
                        {getStatusTag(order.status)}
                      </div>
                    </div>
                    
                    <Divider style={{ margin: '12px 0' }} />
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.buyInfo') || '买入信息'}</div>
                      <div style={{ fontSize: '14px', fontWeight: '500' }}>
                        {t('copyTradingOrders.quantity') || '数量'}: {formatUSDC(order.quantity)} | {t('copyTradingOrders.price') || '价格'}: {formatUSDC(order.price)}
                      </div>
                      <div style={{ fontSize: '14px', fontWeight: '500', marginTop: '4px' }}>
                        {t('copyTradingOrders.amount') || '金额'}: {formatUSDC(amount)} USDC
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.matchInfo') || '匹配信息'}</div>
                      <div style={{ fontSize: '13px', color: '#333' }}>
                        {t('copyTradingOrders.matched') || '已匹配'}: {formatUSDC(order.matchedQuantity)} | {t('copyTradingOrders.remaining') || '剩余'}: {formatUSDC(order.remainingQuantity)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.leaderTradeId') || 'Leader 交易ID'}</div>
                      <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                        {order.leaderTradeId.slice(0, 8)}...{order.leaderTradeId.slice(-6)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '16px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.marketId') || '市场ID'}</div>
                      <div style={{ fontSize: '12px', color: '#999', fontFamily: 'monospace' }}>
                        {order.marketId.slice(0, 8)}...{order.marketId.slice(-6)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '16px' }}>
                      <div style={{ fontSize: '12px', color: '#999' }}>
                        {t('copyTradingOrders.createdAt') || '创建时间'}: {formattedDate}
                      </div>
                    </div>
                  </Card>
                )
              })}
            </div>
          )}
        </div>
      ) : (
        <Table
          columns={columns}
          dataSource={orders}
          rowKey="orderId"
          loading={loading}
          pagination={{
            current: page,
            pageSize: limit,
            total,
            showSizeChanger: true,
            showTotal: (total) => `${t('common.total') || '共'} ${total} ${t('common.items') || '条'}`,
            onChange: (newPage, newLimit) => {
              setPage(newPage)
              setLimit(newLimit)
            }
          }}
        />
      )}
    </div>
  )
}

export default BuyOrdersTab

