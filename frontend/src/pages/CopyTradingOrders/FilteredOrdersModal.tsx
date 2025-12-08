import { useEffect, useState } from 'react'
import { Modal, Table, Tag, Select, Card, Divider, Spin } from 'antd'
import { useTranslation } from 'react-i18next'
import { apiService } from '../../services/api'
import type { FilteredOrder, FilteredOrderListResponse } from '../../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../../utils'

const { Option } = Select

interface FilteredOrdersModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
}

const FilteredOrdersModal: React.FC<FilteredOrdersModalProps> = ({
  open,
  onClose,
  copyTradingId
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [filteredOrders, setFilteredOrders] = useState<FilteredOrder[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit] = useState(20)
  const [filterType, setFilterType] = useState<string | undefined>(undefined)
  
  useEffect(() => {
    if (open && copyTradingId) {
      fetchFilteredOrders()
    }
  }, [open, copyTradingId, page, filterType])
  
  const fetchFilteredOrders = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const response = await apiService.copyTrading.getFilteredOrders({
        copyTradingId: parseInt(copyTradingId),
        filterType: filterType,
        page: page,
        limit: limit
      })
      
      if (response.data.code === 0 && response.data.data) {
        const data: FilteredOrderListResponse = response.data.data
        setFilteredOrders(data.list || [])
        setTotal(data.total || 0)
      }
    } catch (error: any) {
      console.error('获取被过滤订单列表失败:', error)
    } finally {
      setLoading(false)
    }
  }
  
  const getFilterTypeTag = (filterType: string) => {
    const typeMap: Record<string, { color: string; text: string }> = {
      ORDER_DEPTH: { color: 'orange', text: t('filteredOrdersList.filterTypes.orderDepth') || '订单深度不足' },
      SPREAD: { color: 'red', text: t('filteredOrdersList.filterTypes.spread') || '价差过大' },
      ORDERBOOK_DEPTH: { color: 'purple', text: t('filteredOrdersList.filterTypes.orderbookDepth') || '订单簿深度不足' },
      PRICE_VALIDITY: { color: 'blue', text: t('filteredOrdersList.filterTypes.priceValidity') || '价格不合理' },
      MARKET_STATUS: { color: 'default', text: t('filteredOrdersList.filterTypes.marketStatus') || '市场状态不可交易' },
      ORDERBOOK_ERROR: { color: 'default', text: t('filteredOrdersList.filterTypes.orderbookError') || '订单簿获取失败' },
      ORDERBOOK_EMPTY: { color: 'default', text: t('filteredOrdersList.filterTypes.orderbookEmpty') || '订单簿为空' },
      PRICE_RANGE: { color: 'purple', text: t('filteredOrdersList.filterTypes.priceRange') || '价格区间不符' }
    }
    const config = typeMap[filterType] || { color: 'default', text: filterType }
    return <Tag color={config.color}>{config.text}</Tag>
  }
  
  const columns = [
    {
      title: t('filteredOrdersList.market') || '市场',
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      width: isMobile ? 150 : 200,
      ellipsis: true,
      render: (text: string, record: FilteredOrder) => {
        const marketTitle = text || record.marketId.slice(0, 10) + '...'
        return <span style={{ fontSize: isMobile ? 12 : 14 }}>{marketTitle}</span>
      }
    },
    {
      title: t('filteredOrdersList.side') || '方向',
      dataIndex: 'side',
      key: 'side',
      width: isMobile ? 60 : 80,
      render: (side: string) => (
        <Tag color={side === 'BUY' ? 'green' : 'red'}>
          {side === 'BUY' ? (t('order.buy') || '买入') : (t('order.sell') || '卖出')}
        </Tag>
      )
    },
    {
      title: t('filteredOrdersList.price') || '价格',
      dataIndex: 'price',
      key: 'price',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('filteredOrdersList.size') || '数量',
      dataIndex: 'size',
      key: 'size',
      width: isMobile ? 80 : 100,
      render: (value: string) => (
        <span style={{ fontSize: isMobile ? 12 : 14 }}>{formatUSDC(value)}</span>
      )
    },
    {
      title: t('filteredOrdersList.filterType') || '过滤类型',
      dataIndex: 'filterType',
      key: 'filterType',
      width: isMobile ? 120 : 150,
      render: (filterType: string) => getFilterTypeTag(filterType)
    },
    {
      title: t('filteredOrdersList.filterReason') || '过滤原因',
      dataIndex: 'filterReason',
      key: 'filterReason',
      width: isMobile ? 150 : 250,
      ellipsis: true,
      render: (text: string) => (
        <span style={{ fontSize: isMobile ? 11 : 12 }} title={text}>{text}</span>
      )
    },
    {
      title: t('filteredOrdersList.createdAt') || '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 160,
      render: (timestamp: number) => {
        const date = new Date(timestamp)
        const format = isMobile 
          ? `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
          : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
        return (
          <span style={{ fontSize: isMobile ? 11 : 12 }}>
            {format}
          </span>
        )
      }
    }
  ]
  
  return (
    <Modal
      title={t('copyTradingOrders.filteredOrders') || '已过滤订单'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
    >
      <div style={{ marginBottom: 16 }}>
        <Select
          placeholder={t('filteredOrdersList.filterByType') || '按类型筛选'}
          allowClear
          style={{ width: isMobile ? '100%' : 200 }}
          value={filterType}
          onChange={(value) => setFilterType(value || undefined)}
        >
          <Option value="ORDER_DEPTH">{t('filteredOrdersList.filterTypes.orderDepth') || '订单深度不足'}</Option>
          <Option value="SPREAD">{t('filteredOrdersList.filterTypes.spread') || '价差过大'}</Option>
          <Option value="ORDERBOOK_DEPTH">{t('filteredOrdersList.filterTypes.orderbookDepth') || '订单簿深度不足'}</Option>
          <Option value="PRICE_VALIDITY">{t('filteredOrdersList.filterTypes.priceValidity') || '价格不合理'}</Option>
          <Option value="MARKET_STATUS">{t('filteredOrdersList.filterTypes.marketStatus') || '市场状态不可交易'}</Option>
          <Option value="ORDERBOOK_ERROR">{t('filteredOrdersList.filterTypes.orderbookError') || '订单簿获取失败'}</Option>
          <Option value="ORDERBOOK_EMPTY">{t('filteredOrdersList.filterTypes.orderbookEmpty') || '订单簿为空'}</Option>
          <Option value="PRICE_RANGE">{t('filteredOrdersList.filterTypes.priceRange') || '价格区间不符'}</Option>
        </Select>
      </div>
      
      {isMobile ? (
        <div>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px' }}>
              <Spin size="large" />
            </div>
          ) : filteredOrders.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
              {t('filteredOrdersList.noFilteredOrders') || '暂无已过滤订单'}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {filteredOrders.map((order) => {
                const date = new Date(order.createdAt)
                const formattedDate = date.toLocaleString('zh-CN', {
                  year: 'numeric',
                  month: '2-digit',
                  day: '2-digit',
                  hour: '2-digit',
                  minute: '2-digit'
                })
                const marketTitle = order.marketTitle || order.marketId.slice(0, 10) + '...'
                
                return (
                  <Card
                    key={order.id}
                    style={{
                      borderRadius: '12px',
                      boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                      border: '1px solid #e8e8e8'
                    }}
                    bodyStyle={{ padding: '16px' }}
                  >
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ 
                        fontSize: '16px', 
                        fontWeight: 'bold', 
                        marginBottom: '8px',
                        color: '#1890ff'
                      }}>
                        {marketTitle}
                      </div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                        <Tag color={order.side === 'BUY' ? 'green' : 'red'}>
                          {order.side === 'BUY' ? (t('order.buy') || '买入') : (t('order.sell') || '卖出')}
                        </Tag>
                        {getFilterTypeTag(order.filterType)}
                      </div>
                    </div>
                    
                    <Divider style={{ margin: '12px 0' }} />
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('filteredOrdersList.orderDetails') || '订单详情'}</div>
                      <div style={{ fontSize: '13px', color: '#333' }}>
                        {t('filteredOrdersList.price') || '价格'}: {formatUSDC(order.price)} | {t('filteredOrdersList.size') || '数量'}: {formatUSDC(order.size)}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '12px' }}>
                      <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{t('filteredOrdersList.filterReason') || '过滤原因'}</div>
                      <div style={{ fontSize: '12px', color: '#333' }} title={order.filterReason}>
                        {order.filterReason}
                      </div>
                    </div>
                    
                    <div style={{ marginBottom: '16px' }}>
                      <div style={{ fontSize: '12px', color: '#999' }}>
                        {t('filteredOrdersList.createdAt') || '时间'}: {formattedDate}
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
          dataSource={filteredOrders}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page,
            pageSize: limit,
            total,
            showSizeChanger: true,
            showTotal: (total) => `${t('common.total') || '共'} ${total} ${t('common.items') || '条'}`,
            onChange: (newPage) => {
              setPage(newPage)
            }
          }}
        />
      )}
    </Modal>
  )
}

export default FilteredOrdersModal

