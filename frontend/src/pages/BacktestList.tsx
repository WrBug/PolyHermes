import { useState, useEffect } from 'react'
import { Table, Card, Button, Select, Tag, Space, Modal, message, Row, Col } from 'antd'
import { useTranslation } from 'react-i18next'
import { PlusOutlined, ReloadOutlined, DeleteOutlined, StopOutlined, EyeOutlined } from '@ant-design/icons'
import { formatUSDC } from '../utils'
import { backtestService } from '../services/api'
import type { BacktestTaskDto, BacktestListRequest } from '../types/backtest'

const BacktestList: React.FC = () => {
  const { t } = useTranslation()
  const [loading, setLoading] = useState(false)
  const [tasks, setTasks] = useState<BacktestTaskDto[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size] = useState(10)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [leaderIdFilter] = useState<number | undefined>()
  const [sortBy, setSortBy] = useState<'profitAmount' | 'profitRate' | 'createdAt'>('createdAt')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')

  // 获取回测任务列表
  const fetchTasks = async () => {
    setLoading(true)
    try {
      const request: BacktestListRequest = {
        leaderId: leaderIdFilter,
        status: statusFilter as any,
        sortBy,
        sortOrder,
        page,
        size
      }
      const response = await backtestService.list(request)
      if (response.data.code === 0 && response.data.data) {
        setTasks(response.data.data.list)
        setTotal(response.data.data.total)
      } else {
        message.error(response.data.msg || t('backtest.fetchTasksFailed'))
      }
    } catch (error) {
      console.error('Failed to fetch backtest tasks:', error)
      message.error(t('backtest.fetchTasksFailed'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchTasks()
  }, [page, statusFilter, leaderIdFilter, sortBy, sortOrder])

  // 刷新
  const handleRefresh = () => {
    fetchTasks()
  }

  // 删除任务
  const handleDelete = (id: number) => {
    Modal.confirm({
      title: t('backtest.deleteConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.delete({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.deleteSuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.deleteFailed'))
          }
        } catch (error) {
          console.error('Failed to delete backtest task:', error)
          message.error(t('backtest.deleteFailed'))
        }
      }
    })
  }

  // 停止任务
  const handleStop = (id: number) => {
    Modal.confirm({
      title: t('backtest.stopConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.stop({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.stopSuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.stopFailed'))
          }
        } catch (error) {
          console.error('Failed to stop backtest task:', error)
          message.error(t('backtest.stopFailed'))
        }
      }
    })
  }

  // 查看详情
  const handleViewDetail = (id: number) => {
    window.location.href = `/backtest/detail?id=${id}`
  }

  // 创建新任务
  const handleCreate = () => {
    window.location.href = '/backtest/create'
  }

  // 状态标签颜色
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'PENDING': return 'blue'
      case 'RUNNING': return 'processing'
      case 'COMPLETED': return 'success'
      case 'STOPPED': return 'warning'
      case 'FAILED': return 'error'
      default: return 'default'
    }
  }

  // 状态标签文本
  const getStatusText = (status: string) => {
    switch (status) {
      case 'PENDING': return t('backtest.statusPending')
      case 'RUNNING': return t('backtest.statusRunning')
      case 'COMPLETED': return t('backtest.statusCompleted')
      case 'STOPPED': return t('backtest.statusStopped')
      case 'FAILED': return t('backtest.statusFailed')
      default: return status
    }
  }

  const columns = [
    {
      title: t('backtest.taskName'),
      dataIndex: 'taskName',
      key: 'taskName',
      width: 150
    },
    {
      title: t('backtest.leader'),
      dataIndex: 'leaderName',
      key: 'leaderName',
      width: 150
    },
    {
      title: t('backtest.initialBalance'),
      dataIndex: 'initialBalance',
      key: 'initialBalance',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.finalBalance'),
      dataIndex: 'finalBalance',
      key: 'finalBalance',
      width: 120,
      render: (value: string | null) => value ? formatUSDC(value) : '-'
    },
    {
      title: t('backtest.profitAmount'),
      dataIndex: 'profitAmount',
      key: 'profitAmount',
      width: 120,
      render: (value: string | null) => value ? (
        <span style={{ color: parseFloat(value) >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {formatUSDC(value)}
        </span>
      ) : '-'
    },
    {
      title: t('backtest.profitRate'),
      dataIndex: 'profitRate',
      key: 'profitRate',
      width: 100,
      render: (value: string | null) => value ? (
        <span style={{ color: parseFloat(value) >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {value}%
        </span>
      ) : '-'
    },
    {
      title: t('backtest.backtestDays'),
      dataIndex: 'backtestDays',
      key: 'backtestDays',
      width: 100,
      render: (value: number) => `${value} ${t('common.day')}`
    },
    {
      title: t('backtest.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => (
        <Tag color={getStatusColor(status)}>{getStatusText(status)}</Tag>
      )
    },
    {
      title: t('backtest.progress'),
      dataIndex: 'progress',
      key: 'progress',
      width: 120,
      render: (progress: number) => (
        <div style={{ width: '100%' }}>
          <div style={{ marginBottom: 4 }}>{progress}%</div>
          <div style={{ width: '100%', height: 6, backgroundColor: '#f0f0f0', borderRadius: 3 }}>
            <div
              style={{
                width: `${progress}%`,
                height: '100%',
                backgroundColor: progress === 100 ? '#52c41a' : '#1890ff',
                borderRadius: 3,
                transition: 'width 0.3s ease'
              }}
            />
          </div>
        </div>
      )
    },
    {
      title: t('backtest.totalTrades'),
      dataIndex: 'totalTrades',
      key: 'totalTrades',
      width: 100
    },
    {
      title: t('backtest.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: t('common.actions'),
      key: 'actions',
      fixed: 'right' as const,
      width: 150,
      render: (_: any, record: BacktestTaskDto) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => handleViewDetail(record.id)}
          >
            {t('common.viewDetail')}
          </Button>
          {(record.status === 'RUNNING' || record.status === 'PENDING') && (
            <Button
              type="link"
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => handleStop(record.id)}
            >
              {t('backtest.statusStopped')}
            </Button>
          )}
          {(record.status === 'COMPLETED' || record.status === 'STOPPED' || record.status === 'FAILED') && (
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleDelete(record.id)}
            >
              {t('common.delete')}
            </Button>
          )}
        </Space>
      )
    }
  ]

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          {/* 头部操作栏 */}
          <Row justify="space-between" align="middle">
            <Col>
              <Space size="middle">
                <Select
                  style={{ width: 150 }}
                  placeholder={t('backtest.status')}
                  allowClear
                  onChange={(value) => setStatusFilter(value)}
                  value={statusFilter}
                >
                  <Select.Option value="PENDING">{t('backtest.statusPending')}</Select.Option>
                  <Select.Option value="RUNNING">{t('backtest.statusRunning')}</Select.Option>
                  <Select.Option value="COMPLETED">{t('backtest.statusCompleted')}</Select.Option>
                  <Select.Option value="STOPPED">{t('backtest.statusStopped')}</Select.Option>
                  <Select.Option value="FAILED">{t('backtest.statusFailed')}</Select.Option>
                </Select>
                <Select
                  style={{ width: 150 }}
                  placeholder={t('backtest.sortBy')}
                  onChange={(value) => setSortBy(value)}
                  value={sortBy}
                >
                  <Select.Option value="profitAmount">{t('backtest.profitAmount')}</Select.Option>
                  <Select.Option value="profitRate">{t('backtest.profitRate')}</Select.Option>
                  <Select.Option value="createdAt">{t('backtest.createdAt')}</Select.Option>
                </Select>
                <Select
                  style={{ width: 120 }}
                  placeholder={t('backtest.sortOrder')}
                  onChange={(value) => setSortOrder(value)}
                  value={sortOrder}
                >
                  <Select.Option value="asc">{t('common.ascending')}</Select.Option>
                  <Select.Option value="desc">{t('common.descending')}</Select.Option>
                </Select>
              </Space>
            </Col>
            <Col>
              <Space>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreate}
                >
                  {t('backtest.createTask')}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  onClick={handleRefresh}
                  loading={loading}
                >
                  {t('common.refresh')}
                </Button>
              </Space>
            </Col>
          </Row>

          {/* 数据表格 */}
          <Table
            columns={columns}
            dataSource={tasks}
            rowKey="id"
            loading={loading}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: false,
              showTotal: (total) => `${t('common.total')} ${total} ${t('common.items')}`,
              onChange: (newPage) => setPage(newPage)
            }}
            scroll={{ x: 1400 }}
          />
        </Space>
      </Card>
    </div>
  )
}

export default BacktestList

