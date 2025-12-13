import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Input } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, BarChartOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { NbaQuantitativeStrategy } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Option } = Select
const { Search } = Input

const NbaQuantitativeStrategyList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [strategies, setStrategies] = useState<NbaQuantitativeStrategy[]>([])
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<{
    accountId?: number
    enabled?: boolean
    strategyName?: string
  }>({})
  
  useEffect(() => {
    fetchAccounts()
    fetchStrategies()
  }, [])
  
  useEffect(() => {
    fetchStrategies()
  }, [filters])
  
  const fetchStrategies = async () => {
    setLoading(true)
    try {
      const response = await apiService.nbaStrategies.list({
        accountId: filters.accountId,
        enabled: filters.enabled,
        strategyName: filters.strategyName,
        page: 1,
        limit: 100
      })
      if (response.data.code === 0 && response.data.data) {
        setStrategies(response.data.data.list || [])
      } else {
        message.error(response.data.msg || '获取策略列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取策略列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const handleToggleStatus = async (strategy: NbaQuantitativeStrategy) => {
    try {
      const response = await apiService.nbaStrategies.update({
        id: strategy.id!,
        enabled: !strategy.enabled
      })
      if (response.data.code === 0) {
        message.success(strategy.enabled ? '禁用策略成功' : '启用策略成功')
        fetchStrategies()
      } else {
        message.error(response.data.msg || '更新策略状态失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新策略状态失败')
    }
  }
  
  const handleDelete = async (strategyId: number) => {
    try {
      const response = await apiService.nbaStrategies.delete({ id: strategyId })
      if (response.data.code === 0) {
        message.success('删除策略成功')
        fetchStrategies()
      } else {
        message.error(response.data.msg || '删除策略失败')
      }
    } catch (error: any) {
      message.error(error.message || '删除策略失败')
    }
  }
  
  const columns = [
    {
      title: '策略名称',
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: isMobile ? 120 : 200,
      ellipsis: true
    },
    {
      title: '关联账户',
      dataIndex: 'accountName',
      key: 'accountName',
      width: isMobile ? 100 : 150,
      ellipsis: true
    },
    {
      title: '启用状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (enabled: boolean, record: NbaQuantitativeStrategy) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggleStatus(record)}
          size={isMobile ? 'small' : 'default'}
        />
      )
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 120 : 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: '操作',
      key: 'action',
      width: isMobile ? 150 : 200,
      fixed: 'right' as const,
      render: (_: any, record: NbaQuantitativeStrategy) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/nba/strategies/edit/${record.id}`)}
          >
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            icon={<BarChartOutlined />}
            onClick={() => navigate(`/nba/statistics/${record.id}`)}
          >
            统计
          </Button>
          <Popconfirm
            title="确定要删除这个策略吗？"
            onConfirm={() => handleDelete(record.id!)}
            okText="确定"
            cancelText="取消"
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  return (
    <div style={{ padding: isMobile ? '16px' : '24px' }}>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/nba/strategies/add')}
          >
            创建策略
          </Button>
          <Select
            placeholder="选择账户"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.accountId}
            onChange={(value) => setFilters({ ...filters, accountId: value })}
          >
            {accounts.map(account => (
              <Option key={account.id} value={account.id}>
                {account.accountName || account.walletAddress}
              </Option>
            ))}
          </Select>
          <Select
            placeholder="启用状态"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.enabled}
            onChange={(value) => setFilters({ ...filters, enabled: value })}
          >
            <Option value={true}>已启用</Option>
            <Option value={false}>已禁用</Option>
          </Select>
          <Search
            placeholder="搜索策略名称"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            onSearch={(value) => setFilters({ ...filters, strategyName: value })}
          />
        </div>
        
        <Table
          columns={columns}
          dataSource={strategies}
          rowKey="id"
          loading={loading}
          scroll={{ x: isMobile ? 800 : 'auto' }}
          pagination={{
            pageSize: 20,
            showSizeChanger: !isMobile,
            showTotal: (total) => `共 ${total} 条`
          }}
        />
      </Card>
    </div>
  )
}

export default NbaQuantitativeStrategyList

