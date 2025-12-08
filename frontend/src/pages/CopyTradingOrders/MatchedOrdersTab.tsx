import { useEffect, useState } from 'react'
import { Table, Input, Button, Card, Divider, Spin } from 'antd'
import { apiService } from '../../services/api'
import { formatUSDC } from '../../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { MatchedOrderInfo, OrderTrackingRequest, OrderTrackingListResponse } from '../../types'

interface MatchedOrdersTabProps {
  copyTradingId: string
}

const MatchedOrdersTab: React.FC<MatchedOrdersTabProps> = ({ copyTradingId }) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [orders, setOrders] = useState<MatchedOrderInfo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(20)
  const [filters, setFilters] = useState<{
    sellOrderId?: string
    buyOrderId?: string
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
        type: 'matched',
        page,
        limit,
        ...filters
      }
      
      const response = await apiService.orderTracking.list(request)
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data as OrderTrackingListResponse
        setOrders((data.list || []) as MatchedOrderInfo[])
        setTotal(data.total || 0)
      }
    } catch (error: any) {
      console.error('获取匹配关系列表失败:', error)
    } finally {
      setLoading(false)
    }
  }
  
  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const columns = [
    {
      title: t('copyTradingOrders.sellOrderId') || '卖出订单ID',
      dataIndex: 'sellOrderId',
      key: 'sellOrderId',
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
      title: t('copyTradingOrders.buyOrderId') || '买入订单ID',
      dataIndex: 'buyOrderId',
      key: 'buyOrderId',
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
      title: t('copyTradingOrders.matchedQuantity') || '匹配数量',
      dataIndex: 'matchedQuantity',
      key: 'matchedQuantity',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.buyPrice') || '买入价格',
      dataIndex: 'buyPrice',
      key: 'buyPrice',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.sellPrice') || '卖出价格',
      dataIndex: 'sellPrice',
      key: 'sellPrice',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('copyTradingOrders.realizedPnl') || '盈亏',
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      width: isMobile ? 100 : 120,
      render: (value: string) => (
        <span style={{ 
          color: getPnlColor(value), 
          fontWeight: 500,
          fontSize: isMobile ? 12 : 14
        }}>
          {isMobile ? formatUSDC(value) : `${formatUSDC(value)} USDC`}
        </span>
      )
    },
    {
      title: t('copyTradingOrders.matchedAt') || '匹配时间',
      dataIndex: 'matchedAt',
      key: 'matchedAt',
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
          placeholder={t('copyTradingOrders.filterSellOrderId') || '筛选卖出订单ID'}
          allowClear
          style={{ width: isMobile ? '100%' : 200 }}
          value={filters.sellOrderId}
          onChange={(e) => setFilters({ ...filters, sellOrderId: e.target.value || undefined })}
        />
        
        <Input
          placeholder={t('copyTradingOrders.filterBuyOrderId') || '筛选买入订单ID'}
          allowClear
          style={{ width: isMobile ? '100%' : 200 }}
          value={filters.buyOrderId}
          onChange={(e) => setFilters({ ...filters, buyOrderId: e.target.value || undefined })}
        />
        
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
              {t('copyTradingOrders.noMatchedOrders') || '暂无匹配关系'}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {orders.map((order) => {
                const date = new Date(order.matchedAt)
                const formattedDate = date.toLocaleString('zh-CN', {
                  year: 'numeric',
                  month: '2-digit',
                  day: '2-digit',
                  hour: '2-digit',
                  minute: '2-digit'
                })
                
                return (
                  <Card
                    key={`${order.sellOrderId}-${order.buyOrderId}-${order.matchedAt}`}
                    style={{
                      borderRadius: '12px',
                      boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                      border: '1px solid #e8e8e8'
                    }}
                    bodyStyle={{ padding: '16px' }}
                  >
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.sellOrderId') || '卖出订单ID'}</div>
                      <div style={{ 
                        fontSize: '13px', 
                        fontWeight: '500',
                        fontFamily: 'monospace',
                        marginBottom: '8px'
                      }}>
                        {order.sellOrderId.slice(0, 8)}...{order.sellOrderId.slice(-6)}
                      </div>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.buyOrderId') || '买入订单ID'}</div>
                      <div style={{ 
                        fontSize: '13px', 
                        fontWeight: '500',
                        fontFamily: 'monospace'
                      }}>
                        {order.buyOrderId.slice(0, 8)}...{order.buyOrderId.slice(-6)}
                      </div>
                    </div>
                    
                    <Divider style={{ margin: '12px 0' }} />
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.matchedQuantity') || '匹配数量'}</div>
                      <div style={{ fontSize: '14px', fontWeight: '500' }}>
                        {formatUSDC(order.matchedQuantity)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.priceInfo') || '价格信息'}</div>
                      <div style={{ fontSize: '13px', color: '#333' }}>
                        {t('copyTradingOrders.buy') || '买入'}: {formatUSDC(order.buyPrice)} | {t('copyTradingOrders.sell') || '卖出'}: {formatUSDC(order.sellPrice)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '16px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('copyTradingOrders.realizedPnl') || '盈亏'}</div>
                      <div style={{ 
                        fontSize: '16px', 
                        fontWeight: 'bold',
                        color: getPnlColor(order.realizedPnl)
                      }}>
                        {formatUSDC(order.realizedPnl)} USDC
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '16px' }}>
                      <div style={{ fontSize: '12px', color: '#999' }}>
                        {t('copyTradingOrders.matchedAt') || '匹配时间'}: {formattedDate}
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
          rowKey={(record) => `${record.sellOrderId}-${record.buyOrderId}-${record.matchedAt}`}
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

export default MatchedOrdersTab

