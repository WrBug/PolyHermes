import { useEffect, useState } from 'react'
import { Card, Table, Tag, message } from 'antd'
import { apiService } from '../services/api'
import type { CopyOrder } from '../types'
import { useMediaQuery } from 'react-responsive'

const OrderList: React.FC = () => {
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [orders, setOrders] = useState<CopyOrder[]>([])
  const [loading, setLoading] = useState(false)
  const [pagination, setPagination] = useState({
    current: 1,
    pageSize: 20,
    total: 0
  })
  
  useEffect(() => {
    fetchOrders()
  }, [pagination.current, pagination.pageSize])
  
  const fetchOrders = async () => {
    setLoading(true)
    try {
      const response = await apiService.orders.list({
        page: pagination.current,
        limit: pagination.pageSize
      })
      if (response.data.code === 0 && response.data.data) {
        setOrders(response.data.data.list || [])
        setPagination(prev => ({
          ...prev,
          total: response.data.data?.total || 0
        }))
      } else {
        message.error(response.data.msg || '获取订单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取订单列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'filled':
        return 'success'
      case 'cancelled':
        return 'default'
      case 'failed':
        return 'error'
      default:
        return 'processing'
    }
  }
  
  const getSideColor = (side: string) => {
    return side === 'BUY' ? 'green' : 'red'
  }
  
  const columns = [
    {
      title: 'Leader',
      dataIndex: 'leaderName',
      key: 'leaderName',
      render: (text: string, record: CopyOrder) => text || record.leaderAddress.slice(0, 10) + '...'
    },
    {
      title: '市场',
      dataIndex: 'marketId',
      key: 'marketId',
      render: (marketId: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>
          {marketId.slice(0, 10)}...
        </span>
      )
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      render: (category: string) => (
        <Tag color={category === 'sports' ? 'blue' : 'green'}>{category}</Tag>
      )
    },
    {
      title: '方向',
      dataIndex: 'side',
      key: 'side',
      render: (side: string) => (
        <Tag color={getSideColor(side)}>{side}</Tag>
      )
    },
    {
      title: '价格',
      dataIndex: 'price',
      key: 'price'
    },
    {
      title: '数量',
      dataIndex: 'size',
      key: 'size'
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={getStatusColor(status)}>{status}</Tag>
      )
    },
    {
      title: '盈亏',
      dataIndex: 'pnl',
      key: 'pnl',
      render: (pnl: string | undefined) => pnl ? (
        <span style={{ color: pnl.startsWith('-') ? 'red' : 'green' }}>
          {pnl} USDC
        </span>
      ) : '-'
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    }
  ]
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <h2>订单管理</h2>
      </div>
      
      <Card>
        <Table
          dataSource={orders}
          columns={columns}
          rowKey="id"
          loading={loading}
          pagination={{
            current: pagination.current,
            pageSize: pagination.pageSize,
            total: pagination.total,
            showSizeChanger: !isMobile,
            onChange: (page, pageSize) => {
              setPagination(prev => ({ ...prev, current: page, pageSize }))
            }
          }}
          scroll={isMobile ? { x: 800 } : undefined}
        />
      </Card>
    </div>
  )
}

export default OrderList

