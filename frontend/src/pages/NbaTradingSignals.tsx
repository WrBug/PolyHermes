import { useEffect, useState } from 'react'
import { Card, Table, Tag, Select, Input, Space, message, Modal, Descriptions, Button } from 'antd'
import { EyeOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { NbaTradingSignal } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Option } = Select
const { Search } = Input

const NbaTradingSignals: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [signals, setSignals] = useState<NbaTradingSignal[]>([])
  const [loading, setLoading] = useState(false)
  const [detailModalVisible, setDetailModalVisible] = useState(false)
  const [selectedSignal, setSelectedSignal] = useState<NbaTradingSignal | null>(null)
  const [filters, setFilters] = useState<{
    strategyId?: number
    signalType?: string
    signalStatus?: string
  }>({})
  
  useEffect(() => {
    fetchSignals()
  }, [filters])
  
  const fetchSignals = async () => {
    setLoading(true)
    try {
      const response = await apiService.nbaSignals.list({
        strategyId: filters.strategyId,
        signalType: filters.signalType,
        signalStatus: filters.signalStatus,
        page: 1,
        limit: 100
      })
      if (response.data.code === 0 && response.data.data) {
        setSignals(response.data.data.list || [])
      } else {
        message.error(response.data.msg || '获取交易信号列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取交易信号列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const handleViewDetail = async (signal: NbaTradingSignal) => {
    try {
      const response = await apiService.nbaSignals.detail({ id: signal.id })
      if (response.data.code === 0 && response.data.data) {
        setSelectedSignal(response.data.data)
        setDetailModalVisible(true)
      } else {
        message.error(response.data.msg || '获取信号详情失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取信号详情失败')
    }
  }
  
  const getSignalTypeColor = (type: string) => {
    return type === 'BUY' ? 'green' : 'red'
  }
  
  const getSignalStatusColor = (status: string) => {
    switch (status) {
      case 'GENERATED':
        return 'default'
      case 'EXECUTING':
        return 'processing'
      case 'SUCCESS':
        return 'success'
      case 'FAILED':
        return 'error'
      default:
        return 'default'
    }
  }
  
  const getSignalStatusText = (status: string) => {
    switch (status) {
      case 'GENERATED':
        return '已生成'
      case 'EXECUTING':
        return '执行中'
      case 'SUCCESS':
        return '执行成功'
      case 'FAILED':
        return '执行失败'
      default:
        return status
    }
  }
  
  const columns = [
    {
      title: '信号类型',
      dataIndex: 'signalType',
      key: 'signalType',
      width: 100,
      render: (type: string) => (
        <Tag color={getSignalTypeColor(type)}>{type}</Tag>
      )
    },
    {
      title: '策略名称',
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: isMobile ? 120 : 150,
      ellipsis: true
    },
    {
      title: '方向',
      dataIndex: 'direction',
      key: 'direction',
      width: 80,
      render: (direction: string) => (
        <Tag color={direction === 'YES' ? 'blue' : 'orange'}>{direction}</Tag>
      )
    },
    {
      title: '价格',
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (price: string) => parseFloat(price).toFixed(4)
    },
    {
      title: '数量',
      dataIndex: 'quantity',
      key: 'quantity',
      width: 120,
      render: (quantity: string) => formatUSDC(quantity)
    },
    {
      title: '总金额',
      dataIndex: 'totalAmount',
      key: 'totalAmount',
      width: 120,
      render: (amount: string) => `${formatUSDC(amount)} USDC`
    },
    {
      title: '状态',
      dataIndex: 'signalStatus',
      key: 'signalStatus',
      width: 100,
      render: (status: string) => (
        <Tag color={getSignalStatusColor(status)}>{getSignalStatusText(status)}</Tag>
      )
    },
    {
      title: '生成时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right' as const,
      render: (_: any, record: NbaTradingSignal) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => handleViewDetail(record)}
        >
          详情
        </Button>
      )
    }
  ]
  
  return (
    <div style={{ padding: isMobile ? '16px' : '24px' }}>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <Select
            placeholder="信号类型"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.signalType}
            onChange={(value) => setFilters({ ...filters, signalType: value })}
          >
            <Option value="BUY">买入</Option>
            <Option value="SELL">卖出</Option>
          </Select>
          
          <Select
            placeholder="信号状态"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.signalStatus}
            onChange={(value) => setFilters({ ...filters, signalStatus: value })}
          >
            <Option value="GENERATED">已生成</Option>
            <Option value="EXECUTING">执行中</Option>
            <Option value="SUCCESS">执行成功</Option>
            <Option value="FAILED">执行失败</Option>
          </Select>
        </div>
        
        <Table
          columns={columns}
          dataSource={signals}
          rowKey="id"
          loading={loading}
          scroll={{ x: isMobile ? 1000 : 'auto' }}
          pagination={{
            pageSize: 50,
            showSizeChanger: !isMobile,
            showTotal: (total) => `共 ${total} 条`
          }}
        />
      </Card>
      
      <Modal
        title="信号详情"
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={null}
        width={isMobile ? '90%' : 800}
      >
        {selectedSignal && (
          <Descriptions column={1} bordered>
            <Descriptions.Item label="信号ID">{selectedSignal.id}</Descriptions.Item>
            <Descriptions.Item label="信号类型">
              <Tag color={getSignalTypeColor(selectedSignal.signalType)}>
                {selectedSignal.signalType}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="策略名称">{selectedSignal.strategyName || '-'}</Descriptions.Item>
            <Descriptions.Item label="方向">
              <Tag color={selectedSignal.direction === 'YES' ? 'blue' : 'orange'}>
                {selectedSignal.direction}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="价格">{parseFloat(selectedSignal.price).toFixed(4)}</Descriptions.Item>
            <Descriptions.Item label="数量">{formatUSDC(selectedSignal.quantity)}</Descriptions.Item>
            <Descriptions.Item label="总金额">{formatUSDC(selectedSignal.totalAmount)} USDC</Descriptions.Item>
            <Descriptions.Item label="获胜概率">
              {selectedSignal.winProbability ? `${(parseFloat(selectedSignal.winProbability) * 100).toFixed(2)}%` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="交易价值">
              {selectedSignal.tradeValue ? parseFloat(selectedSignal.tradeValue).toFixed(4) : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="触发原因">{selectedSignal.reason || '-'}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={getSignalStatusColor(selectedSignal.signalStatus)}>
                {getSignalStatusText(selectedSignal.signalStatus)}
              </Tag>
            </Descriptions.Item>
            {selectedSignal.executionResult && (
              <Descriptions.Item label="执行结果">{selectedSignal.executionResult}</Descriptions.Item>
            )}
            {selectedSignal.errorMessage && (
              <Descriptions.Item label="错误信息">
                <span style={{ color: 'red' }}>{selectedSignal.errorMessage}</span>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="生成时间">
              {new Date(selectedSignal.createdAt).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">
              {new Date(selectedSignal.updatedAt).toLocaleString()}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  )
}

export default NbaTradingSignals

