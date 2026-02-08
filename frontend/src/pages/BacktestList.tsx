import { useState, useEffect } from 'react'
import { Table, Card, Button, Select, Tag, Space, Modal, message, Row, Col, Form, Input, InputNumber, Switch, Statistic, Descriptions } from 'antd'
import { useTranslation } from 'react-i18next'
import { PlusOutlined, ReloadOutlined, DeleteOutlined, StopOutlined, EyeOutlined, RedoOutlined, CopyOutlined, SyncOutlined } from '@ant-design/icons'
import { formatUSDC } from '../utils'
import { backtestService, apiService } from '../services/api'
import type { BacktestTaskDto, BacktestListRequest, BacktestCreateRequest, BacktestTradeDto } from '../types/backtest'
import type { Leader } from '../types'
import { useMediaQuery } from 'react-responsive'
import AddCopyTradingModal from './CopyTradingOrders/AddModal'
import BacktestChart from './BacktestChart'
import LeaderSelect from '../components/LeaderSelect'

const BacktestList: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [tasks, setTasks] = useState<BacktestTaskDto[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size] = useState(10)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [leaderIdFilter] = useState<number | undefined>()
  const [sortBy, setSortBy] = useState<'profitAmount' | 'profitRate' | 'createdAt'>('createdAt')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')

  // 创建回测 modal 相关状态
  const [createModalVisible, setCreateModalVisible] = useState(false)
  const [createForm] = Form.useForm()
  const [createLoading, setCreateLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')

  // 创建跟单配置 Modal
  const [addCopyTradingModalVisible, setAddCopyTradingModalVisible] = useState(false)
  const [preFilledConfig, setPreFilledConfig] = useState<any>(null)

  // 重新测试 Modal 相关状态
  const [rerunModalVisible, setRerunModalVisible] = useState(false)
  const [rerunTask, setRerunTask] = useState<BacktestTaskDto | null>(null)
  const [rerunTaskName, setRerunTaskName] = useState('')
  const [rerunLoading, setRerunLoading] = useState(false)

  // 任务详情 Modal 相关状态
  const [detailModalVisible, setDetailModalVisible] = useState(false)
  const [detailTask, setDetailTask] = useState<BacktestTaskDto | null>(null)
  const [detailConfig, setDetailConfig] = useState<any>(null)
  const [detailStatistics, setDetailStatistics] = useState<any>(null)
  const [detailTrades, setDetailTrades] = useState<BacktestTradeDto[]>([])
  const [detailAllTrades, setDetailAllTrades] = useState<BacktestTradeDto[]>([])
  const [detailTradesLoading, setDetailTradesLoading] = useState(false)
  const [detailTradesTotal, setDetailTradesTotal] = useState(0)
  const [detailTradesPage, setDetailTradesPage] = useState(1)
  const [detailTradesSize] = useState(20)

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

  // 按配置重新测试（仅已完成任务）
  const handleRerun = (task: BacktestTaskDto) => {
    setRerunTask(task)
    setRerunTaskName(`${task.taskName} (副本)`)
    setRerunModalVisible(true)
  }

  const handleRerunSubmit = async () => {
    if (!rerunTask) return
    setRerunLoading(true)
    try {
      const response = await backtestService.rerun({
        id: rerunTask.id,
        taskName: rerunTaskName.trim() || undefined
      })
      if (response.data.code === 0) {
        message.success(t('backtest.rerunSuccess'))
        setRerunModalVisible(false)
        setRerunTask(null)
        setRerunTaskName('')
        fetchTasks()
      } else {
        message.error(response.data.msg || t('backtest.rerunFailed'))
      }
    } catch (error) {
      console.error('Rerun backtest failed:', error)
      message.error(t('backtest.rerunFailed'))
    } finally {
      setRerunLoading(false)
    }
  }

  // 重试任务
  const handleRetry = (id: number) => {
    Modal.confirm({
      title: t('backtest.retryConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.retry({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.retrySuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.retryFailed'))
          }
        } catch (error) {
          console.error('Failed to retry backtest task:', error)
          message.error(t('backtest.retryFailed'))
        }
      }
    })
  }

  // 获取 Leader 列表
  useEffect(() => {
    if (createModalVisible) {
      const fetchLeaders = async () => {
        try {
          const response = await apiService.leaders.list({})
          if (response.data.code === 0 && response.data.data) {
            setLeaders(response.data.data.list || [])
          }
        } catch (error) {
          console.error('Failed to fetch leaders:', error)
        }
      }
      fetchLeaders()
    }
  }, [createModalVisible])

  // 打开创建 modal
  const handleCreate = () => {
    setCreateModalVisible(true)
    createForm.resetFields()
    createForm.setFieldsValue({
      copyMode: 'RATIO',
      copyRatio: 100, // 默认 100%（显示为百分比）
      maxOrderSize: 1000,
      minOrderSize: 1,
      maxDailyLoss: 500,
      maxDailyOrders: 50,
      supportSell: true,
      keywordFilterMode: 'DISABLED',
      backtestDays: 7
    })
    setCopyMode('RATIO')
  }

  // 提交创建回测任务
  const handleCreateSubmit = async () => {
    try {
      const values = await createForm.validateFields()
      setCreateLoading(true)

      const request: BacktestCreateRequest = {
        taskName: values.taskName,
        leaderId: values.leaderId,
        initialBalance: values.initialBalance,
        backtestDays: values.backtestDays,
        copyMode: values.copyMode || 'RATIO',
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount : undefined,
        maxOrderSize: values.maxOrderSize,
        minOrderSize: values.minOrderSize,
        maxDailyLoss: values.maxDailyLoss,
        maxDailyOrders: values.maxDailyOrders,
        supportSell: values.supportSell,
        keywordFilterMode: values.keywordFilterMode,
        keywords: values.keywords
      }

      const response = await backtestService.create(request)
      if (response.data.code === 0) {
        message.success(t('backtest.createSuccess'))
        setCreateModalVisible(false)
        createForm.resetFields()
        fetchTasks()
      } else {
        message.error(response.data.msg || t('backtest.createFailed'))
      }
    } catch (error) {
      console.error('Failed to create backtest task:', error)
      message.error(t('backtest.createFailed'))
    } finally {
      setCreateLoading(false)
    }
  }

  // 一键创建跟单配置
  const handleCreateCopyTrading = async (task: BacktestTaskDto) => {
    console.log('[BacktestList] handleCreateCopyTrading called, task:', task)
    
    try {
      // 先获取任务详情以获取配置信息
      const response = await backtestService.detail({ id: task.id })
      if (response.data.code === 0 && response.data.data) {
        const taskDetail = response.data.data.task
        const taskConfig = response.data.data.config
        
        console.log('[BacktestList] Fetched task detail, config:', taskConfig)
        
        if (!taskConfig) {
          message.error(t('backtest.fetchTaskDetailFailed') || '获取任务配置失败')
          return
        }

        // 预填充回测任务的配置参数（从 config 中获取）
        const preFilled = {
          leaderId: taskDetail.leaderId,
          copyMode: taskConfig.copyMode,
          copyRatio: taskConfig.copyMode === 'RATIO' ? parseFloat(taskConfig.copyRatio) * 100 : undefined,
          fixedAmount: taskConfig.copyMode === 'FIXED' ? taskConfig.fixedAmount : undefined,
          maxOrderSize: parseFloat(taskConfig.maxOrderSize),
          minOrderSize: parseFloat(taskConfig.minOrderSize),
          maxDailyLoss: parseFloat(taskConfig.maxDailyLoss),
          maxDailyOrders: taskConfig.maxDailyOrders,
          supportSell: taskConfig.supportSell,
          keywordFilterMode: taskConfig.keywordFilterMode || 'DISABLED',
          keywords: taskConfig.keywords || [],
          configName: `回测任务-${taskDetail.taskName}`
        }

        console.log('[BacktestList] Generated preFilled config:', preFilled)
        console.log('[BacktestList] Setting preFilledConfig and opening modal')
        setPreFilledConfig(preFilled)
        setAddCopyTradingModalVisible(true)
      } else {
        message.error(response.data.msg || t('backtest.fetchTaskDetailFailed') || '获取任务详情失败')
      }
    } catch (error) {
      console.error('[BacktestList] Failed to fetch task detail:', error)
      message.error(t('backtest.fetchTaskDetailFailed') || '获取任务详情失败')
    }
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

  // 获取交易记录
  const fetchDetailTrades = async (taskId: number, page: number) => {
    setDetailTradesLoading(true)
    try {
      const response = await backtestService.trades({
        taskId,
        page,
        size: detailTradesSize
      })
      if (response.data.code === 0 && response.data.data) {
        setDetailTrades(response.data.data.list)
        setDetailTradesTotal(response.data.data.total)
      }
    } catch (error) {
      console.error('Failed to fetch trades:', error)
    } finally {
      setDetailTradesLoading(false)
    }
  }

  // 获取所有交易记录（用于图表显示）
  const fetchDetailAllTrades = async (taskId: number) => {
    try {
      const response = await backtestService.trades({
        taskId,
        page: 1,
        size: 10000  // 获取所有数据
      })
      if (response.data.code === 0 && response.data.data) {
        setDetailAllTrades(response.data.data.list)
      }
    } catch (error) {
      console.error('Failed to fetch all trades for chart:', error)
    }
  }

  // 查看详情 - 打开 Modal 而不是跳转页面
  const handleViewDetail = async (id: number) => {
    try {
      const response = await backtestService.detail({ id })
      if (response.data.code === 0 && response.data.data) {
        console.log('[BacktestList] Fetched task detail:', response.data.data)
        console.log('[BacktestList] Statistics:', response.data.data.statistics)
        setDetailTask(response.data.data.task)
        setDetailConfig(response.data.data.config)
        setDetailStatistics(response.data.data.statistics)
        setDetailModalVisible(true)
        // 获取交易记录
        fetchDetailTrades(id, 1)
        fetchDetailAllTrades(id)
      } else {
        message.error(response.data.msg || t('backtest.fetchTaskDetailFailed'))
      }
    } catch (error) {
      console.error('Failed to fetch backtest task detail:', error)
      message.error(t('backtest.fetchTaskDetailFailed'))
    }
  }

  // 交易记录表格列定义
  const tradeColumns = [
    {
      title: t('backtest.tradeTime'),
      dataIndex: 'tradeTime',
      key: 'tradeTime',
      width: 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: t('backtest.marketTitle'),
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      width: 250,
      ellipsis: true
    },
    {
      title: t('backtest.side'),
      dataIndex: 'side',
      key: 'side',
      width: 100,
      render: (side: string) => (
        <Tag color={side === 'BUY' ? 'green' : side === 'SELL' ? 'orange' : 'blue'}>
          {side === 'BUY' ? t('backtest.sideBuy') : side === 'SELL' ? t('backtest.sideSell') : t('backtest.sideSettlement')}
        </Tag>
      )
    },
    {
      title: t('backtest.outcome'),
      dataIndex: 'outcome',
      key: 'outcome',
      width: 100
    },
    {
      title: t('backtest.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: 100,
      render: (value: string) => parseFloat(value).toFixed(4)
    },
    {
      title: t('backtest.price'),
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (value: string) => parseFloat(value).toFixed(4)
    },
    {
      title: t('backtest.amount') + ' (USDC)',
      dataIndex: 'amount',
      key: 'amount',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.balanceAfter') + ' (USDC)',
      dataIndex: 'balanceAfter',
      key: 'balanceAfter',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.leaderTradeId'),
      dataIndex: 'leaderTradeId',
      key: 'leaderTradeId',
      width: 150,
      ellipsis: true
    }
  ]

  const columns = [
    {
      title: t('backtest.taskName'),
      dataIndex: 'taskName',
      key: 'taskName',
      width: isMobile ? 120 : 150
    },
    {
      title: t('backtest.leader'),
      dataIndex: 'leaderName',
      key: 'leaderName',
      width: isMobile ? 100 : 150,
      render: (_: any, record: BacktestTaskDto) => record.leaderName || record.leaderAddress?.substring(0, 8) + '...' || '-'
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
      width: isMobile ? 150 : 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: t('common.actions'),
      key: 'actions',
      fixed: isMobile ? false : ('right' as const),
      width: isMobile ? 100 : 150,
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
          {record.status === 'COMPLETED' && (
            <>
              <Button
                type="link"
                size="small"
                icon={<SyncOutlined />}
                onClick={() => handleRerun(record)}
              >
                {t('backtest.rerun')}
              </Button>
              <Button
                type="link"
                size="small"
                icon={<CopyOutlined />}
                onClick={() => handleCreateCopyTrading(record)}
              >
                {t('backtest.createCopyTrading')}
              </Button>
            </>
          )}
          {record.status === 'RUNNING' && (
            <Button
              type="link"
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => handleStop(record.id)}
            >
              {t('backtest.stop')}
            </Button>
          )}
          {(record.status === 'STOPPED' || record.status === 'FAILED') && (
            <Button
              type="link"
              size="small"
              icon={<RedoOutlined />}
              onClick={() => handleRetry(record.id)}
            >
              {t('backtest.retry')}
            </Button>
          )}
          {(record.status === 'PENDING' || record.status === 'COMPLETED') && (
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
          <Row justify="space-between" align="middle" gutter={[16, 16]}>
            <Col xs={24} sm={24} md={12} lg={16}>
              <Space size="middle" direction={isMobile ? 'vertical' : 'horizontal'} style={{ width: isMobile ? '100%' : 'auto' }}>
                <Select
                  style={{ width: isMobile ? '100%' : 150 }}
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
                  style={{ width: isMobile ? '100%' : 150 }}
                  placeholder={t('backtest.sortBy')}
                  onChange={(value) => setSortBy(value)}
                  value={sortBy}
                >
                  <Select.Option value="profitAmount">{t('backtest.profitAmount')}</Select.Option>
                  <Select.Option value="profitRate">{t('backtest.profitRate')}</Select.Option>
                  <Select.Option value="createdAt">{t('backtest.createdAt')}</Select.Option>
                </Select>
                <Select
                  style={{ width: isMobile ? '100%' : 120 }}
                  placeholder={t('backtest.sortOrder')}
                  onChange={(value) => setSortOrder(value)}
                  value={sortOrder}
                >
                  <Select.Option value="asc">{t('common.ascending')}</Select.Option>
                  <Select.Option value="desc">{t('common.descending')}</Select.Option>
                </Select>
              </Space>
            </Col>
            <Col xs={24} sm={24} md={12} lg={8} style={{ textAlign: isMobile ? 'left' : 'right' }}>
              <Space style={{ width: isMobile ? '100%' : 'auto' }}>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreate}
                  style={{ flex: isMobile ? 1 : undefined }}
                >
                  {isMobile ? t('common.create') : t('backtest.createTask')}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  onClick={handleRefresh}
                  loading={loading}
                  style={{ flex: isMobile ? 1 : undefined }}
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
              onChange: (newPage) => setPage(newPage),
              simple: isMobile
            }}
            scroll={isMobile ? { x: 1200 } : { x: 1400 }}
          />
        </Space>
      </Card>

      {/* 重新测试 Modal */}
      <Modal
        title={t('backtest.rerun')}
        open={rerunModalVisible}
        onCancel={() => {
          setRerunModalVisible(false)
          setRerunTask(null)
          setRerunTaskName('')
        }}
        onOk={handleRerunSubmit}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={rerunLoading}
        destroyOnClose
      >
        <p style={{ marginBottom: 8 }}>{t('backtest.rerunConfirm')}</p>
        <Input
          value={rerunTaskName}
          onChange={(e) => setRerunTaskName(e.target.value)}
          placeholder={t('backtest.rerunTaskNamePlaceholder')}
          maxLength={100}
        />
      </Modal>

      {/* 创建回测任务 Modal */}
      <Modal
        title={t('backtest.createTask')}
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false)
          createForm.resetFields()
        }}
        onOk={handleCreateSubmit}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={isMobile ? '95%' : 800}
        confirmLoading={createLoading}
        destroyOnClose
        style={{ top: isMobile ? 10 : 20 }}
        bodyStyle={{ maxHeight: isMobile ? 'calc(100vh - 150px)' : 'calc(100vh - 200px)', overflowY: 'auto' }}
      >
        <Form
          form={createForm}
          layout="vertical"
          initialValues={{
            maxDailyLoss: 500,
            maxDailyOrders: 50,
            supportSell: true,
            keywordFilterMode: 'DISABLED',
            backtestDays: 7
          }}
        >
          <Row gutter={24}>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.taskName')}
                name="taskName"
                rules={[{ required: true, message: t('backtest.taskNameRequired') || '请输入任务名称' }]}
              >
                <Input placeholder={t('backtest.taskName')} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.leader')}
                name="leaderId"
                rules={[{ required: true, message: t('backtest.leaderRequired') || '请选择 Leader' }]}
              >
                <LeaderSelect
                  leaders={leaders}
                  placeholder={t('backtest.leader')}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={24}>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.initialBalance') + ' (USDC)'}
                name="initialBalance"
                rules={[
                  { required: true, message: t('backtest.initialBalanceRequired') || '请输入初始资金' },
                  { type: 'number', min: 1, message: t('backtest.initialBalanceInvalid') || '初始资金必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.initialBalance')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.backtestDays') + ` (1-15 ${t('common.day')})`}
                name="backtestDays"
                rules={[
                  { required: true, message: t('backtest.backtestDaysRequired') || '请输入回测天数' },
                  { type: 'number', min: 1, max: 15, message: t('backtest.backtestDaysInvalid') || '回测天数必须在 1-15 之间' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.backtestDays')}
                  precision={0}
                  min={1}
                  max={15}
                />
              </Form.Item>
            </Col>
          </Row>

          {/* 跟单配置 */}
          <div style={{ marginBottom: 24 }}>
            <h3 style={{ marginBottom: 16 }}>{t('backtest.config')}</h3>

            <Form.Item
              label={t('backtest.copyMode')}
              name="copyMode"
            >
              <Select onChange={(value) => setCopyMode(value)}>
                <Option value="RATIO">{t('backtest.copyModeRatio')}</Option>
                <Option value="FIXED">{t('backtest.copyModeFixed')}</Option>
              </Select>
            </Form.Item>

            {copyMode === 'RATIO' && (
              <Form.Item
                label={t('backtest.copyRatio')}
                name="copyRatio"
                tooltip={t('backtest.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
                rules={[
                  { required: true, message: t('backtest.copyRatioRequired') || '请输入跟单比例' },
                  { type: 'number', min: 0.01, max: 10000, message: t('backtest.copyRatioInvalid') || '跟单比例必须在 0.01-10000 之间' }
                ]}
              >
                <InputNumber
                  min={0.01}
                  max={10000}
                  step={0.01}
                  precision={2}
                  style={{ width: '100%' }}
                  addonAfter="%"
                  placeholder={t('backtest.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
                  parser={(value) => {
                    const parsed = parseFloat(value || '0')
                    if (parsed > 10000) return 10000
                    return parsed
                  }}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    if (num > 10000) return '10000'
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            )}

            {copyMode === 'FIXED' && (
              <Form.Item
                label={t('backtest.fixedAmount') + ' (USDC)'}
                name="fixedAmount"
                rules={[
                  { required: true, message: t('backtest.fixedAmountRequired') || '请输入固定金额' },
                  { type: 'number', min: 1, message: t('backtest.fixedAmountInvalid') || '固定金额必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.fixedAmount')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            )}

            <Row gutter={24}>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxOrderSize') + ' (USDC)'}
                  name="maxOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.minOrderSize') + ' (USDC)'}
                  name="minOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={24}>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxDailyLoss') + ' (USDC)'}
                  name="maxDailyLoss"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={0} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxDailyOrders')}
                  name="maxDailyOrders"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={0} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              label={t('backtest.supportSell')}
              name="supportSell"
              valuePropName="checked"
            >
              <Switch />
              <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>{t('backtest.supportSellHint') || '是否跟随 Leader 卖出'}</span>
            </Form.Item>

            <Form.Item
              label={t('backtest.keywordFilterMode')}
              name="keywordFilterMode"
            >
              <Select>
                <Option value="DISABLED">{t('backtest.keywordFilterModeDisabled')}</Option>
                <Option value="WHITELIST">{t('backtest.keywordFilterModeWhitelist')}</Option>
                <Option value="BLACKLIST">{t('backtest.keywordFilterModeBlacklist')}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              label={t('backtest.keywords')}
              name="keywords"
            >
              <Select
                mode="tags"
                style={{ width: '100%' }}
                placeholder={t('backtest.keywordsPlaceholder') || '请输入关键字，按回车添加'}
              />
            </Form.Item>
          </div>
        </Form>
      </Modal>

      {/* 创建跟单配置 Modal */}
      <AddCopyTradingModal
        open={addCopyTradingModalVisible}
        onClose={() => {
          setAddCopyTradingModalVisible(false)
          setPreFilledConfig(null)
        }}
        onSuccess={() => {
          message.success(t('backtest.createCopyTradingSuccess'))
          setAddCopyTradingModalVisible(false)
          setPreFilledConfig(null)
        }}
        preFilledConfig={preFilledConfig}
      />

      {/* 任务详情 Modal */}
      <Modal
        title={t('backtest.taskDetail')}
        open={detailModalVisible}
        onCancel={() => {
          setDetailModalVisible(false)
          setDetailTask(null)
          setDetailConfig(null)
          setDetailStatistics(null)
          setDetailTrades([])
          setDetailAllTrades([])
          setDetailTradesPage(1)
          setDetailTradesTotal(0)
        }}
        footer={
          detailTask && detailTask.status === 'COMPLETED' && detailConfig ? (
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button icon={<SyncOutlined />} onClick={() => {
                setDetailModalVisible(false)
                handleRerun(detailTask)
              }}>
                {t('backtest.rerun')}
              </Button>
              <Button type="primary" icon={<CopyOutlined />} onClick={() => {
                const preFilled = {
                  leaderId: detailTask.leaderId,
                  copyMode: detailConfig.copyMode,
                  copyRatio: detailConfig.copyMode === 'RATIO' ? parseFloat(detailConfig.copyRatio) * 100 : undefined,
                  fixedAmount: detailConfig.copyMode === 'FIXED' ? detailConfig.fixedAmount : undefined,
                  maxOrderSize: parseFloat(detailConfig.maxOrderSize),
                  minOrderSize: parseFloat(detailConfig.minOrderSize),
                  maxDailyLoss: parseFloat(detailConfig.maxDailyLoss),
                  maxDailyOrders: detailConfig.maxDailyOrders,
                  supportSell: detailConfig.supportSell,
                  keywordFilterMode: detailConfig.keywordFilterMode,
                  keywords: detailConfig.keywords || [],
                  configName: `回测任务-${detailTask.taskName}`
                }
                setPreFilledConfig(preFilled)
                setAddCopyTradingModalVisible(true)
                setDetailModalVisible(false)
              }}>
                {t('backtest.createCopyTrading')}
              </Button>
            </Space>
          ) : null
        }
        width={isMobile ? '95%' : '80%'}
        style={{ top: isMobile ? 10 : 20 }}
        styles={{
          body: {
            maxHeight: 'calc(100vh - 250px)',
            overflowY: 'auto',
            padding: '24px'
          }
        }}
        destroyOnClose
      >
        {detailTask && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            {/* 任务基本信息 */}
            <Card title={t('backtest.taskDetail')} size="small">
              <Descriptions column={isMobile ? 1 : 2} bordered size="small">
                <Descriptions.Item label={t('backtest.taskName')}>{detailTask.taskName}</Descriptions.Item>
                <Descriptions.Item label={t('backtest.leader')}>
                  {detailTask.leaderName || detailTask.leaderAddress}
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.initialBalance')}>
                  {formatUSDC(detailTask.initialBalance)} USDC
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.finalBalance')}>
                  {detailTask.finalBalance ? formatUSDC(detailTask.finalBalance) + ' USDC' : '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.profitAmount')}>
                  <span style={{ color: detailTask.profitAmount && parseFloat(detailTask.profitAmount) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                    {detailTask.profitAmount ? formatUSDC(detailTask.profitAmount) + ' USDC' : '-'}
                  </span>
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.profitRate')}>
                  <span style={{ color: detailTask.profitRate && parseFloat(detailTask.profitRate) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                    {detailTask.profitRate ? detailTask.profitRate + '%' : '-'}
                  </span>
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.backtestDays')}>
                  {detailTask.backtestDays} {t('common.day')}
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.status')}>
                  <Tag color={getStatusColor(detailTask.status)}>{getStatusText(detailTask.status)}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.progress')}>
                  {detailTask.progress}%
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.totalTrades')}>
                  {detailTask.totalTrades}
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.startTime')}>
                  {new Date(detailTask.startTime).toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label={t('backtest.endTime')}>
                  {detailTask.endTime ? new Date(detailTask.endTime).toLocaleString() : '-'}
                </Descriptions.Item>
              </Descriptions>
            </Card>

            {/* 统计信息 */}
            {detailStatistics && (
              <Card title={t('backtest.statistics')} size="small">
                <Row gutter={16}>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.totalTrades')}
                      value={detailStatistics.totalTrades || 0}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.buyTrades')}
                      value={detailStatistics.buyTrades || 0}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.sellTrades')}
                      value={detailStatistics.sellTrades}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.winTrades')}
                      value={detailStatistics.winTrades}
                      valueStyle={{ color: '#52c41a' }}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.lossTrades')}
                      value={detailStatistics.lossTrades}
                      valueStyle={{ color: '#ff4d4f' }}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.winRate')}
                      value={detailStatistics.winRate || '0.00'}
                      suffix="%"
                      valueStyle={{ 
                        color: detailStatistics.winRate && !isNaN(parseFloat(detailStatistics.winRate)) && parseFloat(detailStatistics.winRate) >= 50 ? '#52c41a' : '#ff4d4f' 
                      }}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.maxProfit')}
                      value={formatUSDC(detailStatistics.maxProfit)}
                      valueStyle={{ color: '#52c41a' }}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.maxLoss')}
                      value={formatUSDC(detailStatistics.maxLoss)}
                      valueStyle={{ color: '#ff4d4f' }}
                    />
                  </Col>
                  <Col xs={24} sm={12} md={6}>
                    <Statistic
                      title={t('backtest.maxDrawdown')}
                      value={formatUSDC(detailStatistics.maxDrawdown)}
                      valueStyle={{ color: '#ff4d4f' }}
                    />
                  </Col>
                  {detailStatistics.avgHoldingTime && (
                    <Col xs={24} sm={12} md={6}>
                      <Statistic
                        title={t('backtest.avgHoldingTime')}
                        value={(detailStatistics.avgHoldingTime / 1000 / 60).toFixed(2)}
                        suffix=" min"
                      />
                    </Col>
                  )}
                </Row>
              </Card>
            )}

            {/* 配置信息 */}
            {detailConfig && (
              <Card title={t('backtest.config')} size="small">
                <Descriptions column={isMobile ? 1 : 2} bordered size="small">
                  <Descriptions.Item label={t('backtest.copyMode')}>
                    {detailConfig.copyMode === 'RATIO' 
                      ? `${t('backtest.copyModeRatio')} ${parseFloat(detailConfig.copyRatio) * 100}%`
                      : `${t('backtest.copyModeFixed')} ${formatUSDC(detailConfig.fixedAmount)} USDC`
                    }
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.maxOrderSize')}>
                    {formatUSDC(detailConfig.maxOrderSize)} USDC
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.minOrderSize')}>
                    {formatUSDC(detailConfig.minOrderSize)} USDC
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.maxDailyLoss')}>
                    {formatUSDC(detailConfig.maxDailyLoss)} USDC
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.maxDailyOrders')}>
                    {detailConfig.maxDailyOrders}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.supportSell')}>
                    {detailConfig.supportSell ? t('common.yes') : t('common.no')}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('backtest.keywordFilterMode')}>
                    {detailConfig.keywordFilterMode === 'WHITELIST'
                      ? t('backtest.keywordFilterModeWhitelist')
                      : detailConfig.keywordFilterMode === 'BLACKLIST'
                        ? t('backtest.keywordFilterModeBlacklist')
                        : t('backtest.keywordFilterModeDisabled')}
                  </Descriptions.Item>
                  {detailConfig.keywords && detailConfig.keywords.length > 0 && (
                    <Descriptions.Item label={t('backtest.keywords')}>
                      {detailConfig.keywords.join(', ')}
                    </Descriptions.Item>
                  )}
                </Descriptions>
              </Card>
            )}

            {/* 资金变化图表 */}
            {detailAllTrades.length > 0 && (
              <Card title={t('backtest.balanceChart')} size="small">
                <BacktestChart trades={detailAllTrades} />
              </Card>
            )}

            {/* 交易记录 */}
            <Card title={t('backtest.tradeRecords')} size="small">
              <Table
                columns={tradeColumns}
                dataSource={detailTrades}
                rowKey="id"
                loading={detailTradesLoading}
                pagination={{
                  current: detailTradesPage,
                  pageSize: detailTradesSize,
                  total: detailTradesTotal,
                  showSizeChanger: false,
                  showTotal: (total) => `${t('common.total')} ${total} ${t('common.items')}`,
                  onChange: (newPage) => {
                    setDetailTradesPage(newPage)
                    if (detailTask) {
                      fetchDetailTrades(detailTask.id, newPage)
                    }
                  }
                }}
                scroll={isMobile ? { x: 1200 } : { x: 1800 }}
                size="small"
              />
            </Card>
          </Space>
        )}
      </Modal>
    </div >
  )
}

export default BacktestList
