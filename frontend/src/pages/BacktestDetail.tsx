import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { Card, Descriptions, Button, Tag, Space, Table, message, Row, Col, Statistic, Spin } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, DeleteOutlined, StopOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { formatUSDC } from '../utils'
import { backtestService } from '../services/api'
import type { BacktestTaskDto, BacktestConfigDto, BacktestStatisticsDto, BacktestTradeDto } from '../types/backtest'
import BacktestChart from './BacktestChart'

const BacktestDetail: React.FC = () => {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const [loading, setLoading] = useState(false)
  const [task, setTask] = useState<BacktestTaskDto | null>(null)
  const [, setConfig] = useState<BacktestConfigDto | null>(null)
  const [statistics, setStatistics] = useState<BacktestStatisticsDto | null>(null)
  const [trades, setTrades] = useState<BacktestTradeDto[]>([])
  const [tradesLoading, setTradesLoading] = useState(false)
  const [tradesTotal, setTradesTotal] = useState(0)
  const [tradesPage, setTradesPage] = useState(1)
  const [tradesSize] = useState(20)
  const [polling, setPolling] = useState<NodeJS.Timeout | null>(null)

  // 获取回测任务详情
  const fetchTaskDetail = async () => {
    setLoading(true)
    try {
      const response = await backtestService.detail({ id: parseInt(id!) })
      if (response.data.code === 0 && response.data.data) {
        setTask(response.data.data.task)
        setConfig(response.data.data.config)
        setStatistics(response.data.data.statistics)
      } else {
        message.error(response.data.msg || t('backtest.fetchTaskDetailFailed'))
      }
    } catch (error) {
      console.error('Failed to fetch backtest task detail:', error)
      message.error(t('backtest.fetchTaskDetailFailed'))
    } finally {
      setLoading(false)
    }
  }

  // 获取交易记录
  const fetchTrades = async (page: number) => {
    setTradesLoading(true)
    try {
      const response = await backtestService.trades({
        taskId: parseInt(id!),
        page,
        size: tradesSize
      })
      if (response.data.code === 0 && response.data.data) {
        setTrades(response.data.data.list)
        setTradesTotal(response.data.data.total)
      } else {
        message.error(response.data.msg || t('backtest.fetchTradesFailed'))
      }
    } catch (error) {
      console.error('Failed to fetch backtest trades:', error)
      message.error(t('backtest.fetchTradesFailed'))
    } finally {
      setTradesLoading(false)
    }
  }

  useEffect(() => {
    fetchTaskDetail()
    fetchTrades(tradesPage)

    // 如果任务正在运行，启动轮询
    const startPolling = () => {
      const timer = setInterval(() => {
        fetchTaskDetail()
        // 如果任务已完成或失败，停止轮询
        if (task?.status === 'COMPLETED' || task?.status === 'FAILED' || task?.status === 'STOPPED') {
          stopPolling()
        }
      }, 3000) // 每3秒轮询一次
      setPolling(timer)
    }

    // 延迟启动轮询，等待任务状态加载完成
    setTimeout(() => {
      if (task?.status === 'RUNNING' || task?.status === 'PENDING') {
        startPolling()
      }
    }, 1000)

    return () => {
      stopPolling()
    }
  }, [id, task?.status])

  const stopPolling = () => {
    if (polling) {
      clearInterval(polling)
      setPolling(null)
    }
  }

  // 返回
  const handleBack = () => {
    window.location.href = '/backtest/list'
  }

  // 停止任务
  const handleStop = () => {
    if (!window.confirm(t('backtest.stopConfirm'))) return

    const stop = async () => {
      try {
        const response = await backtestService.stop({ id: parseInt(id!) })
        if (response.data.code === 0) {
          message.success(t('backtest.stopSuccess'))
          fetchTaskDetail()
          stopPolling()
        } else {
          message.error(response.data.msg || t('backtest.stopFailed'))
        }
      } catch (error) {
        console.error('Failed to stop backtest task:', error)
        message.error(t('backtest.stopFailed'))
      }
    }
    stop()
  }

  // 删除任务
  const handleDelete = () => {
    if (!window.confirm(t('backtest.deleteConfirm'))) return

    const del = async () => {
      try {
        const response = await backtestService.delete({ id: parseInt(id!) })
        if (response.data.code === 0) {
          message.success(t('backtest.deleteSuccess'))
          window.location.href = '/backtest/list'
        } else {
          message.error(response.data.msg || t('backtest.deleteFailed'))
        }
      } catch (error) {
        console.error('Failed to delete backtest task:', error)
        message.error(t('backtest.deleteFailed'))
      }
    }
    del()
  }

  // 刷新
  const handleRefresh = () => {
    fetchTaskDetail()
    fetchTrades(tradesPage)
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
      title: t('backtest.fee') + ' (USDC)',
      dataIndex: 'fee',
      key: 'fee',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.profitLoss') + ' (USDC)',
      dataIndex: 'profitLoss',
      key: 'profitLoss',
      width: 120,
      render: (value: string | null) => value ? (
        <span style={{ color: parseFloat(value) >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {formatUSDC(value)}
        </span>
      ) : '-'
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

  if (!task) {
    return <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div>
  }

  return (
    <div style={{ padding: 24 }}>
      <Card>
        {/* 头部操作栏 */}
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Space>
              <Button icon={<ArrowLeftOutlined />} onClick={handleBack}>
                {t('common.back')}
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleRefresh} loading={loading}>
                {t('common.refresh')}
              </Button>
            </Space>
            <Space>
              {(task.status === 'RUNNING' || task.status === 'PENDING') && (
                <Button danger icon={<StopOutlined />} onClick={handleStop}>
                  {t('backtest.statusStopped')}
                </Button>
              )}
              {(task.status === 'COMPLETED' || task.status === 'STOPPED' || task.status === 'FAILED') && (
                <Button danger icon={<DeleteOutlined />} onClick={handleDelete}>
                  {t('common.delete')}
                </Button>
              )}
            </Space>
          </Space>

          {/* 任务基本信息 */}
          <Card title={t('backtest.taskDetail')} size="small">
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label={t('backtest.taskName')}>{task.taskName}</Descriptions.Item>
              <Descriptions.Item label={t('backtest.leader')}>
                {task.leaderName || task.leaderAddress}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.initialBalance')}>
                {formatUSDC(task.initialBalance)} USDC
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.finalBalance')}>
                {task.finalBalance ? formatUSDC(task.finalBalance) + ' USDC' : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.profitAmount')}>
                <span style={{ color: task.profitAmount && parseFloat(task.profitAmount) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                  {task.profitAmount ? formatUSDC(task.profitAmount) + ' USDC' : '-'}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.profitRate')}>
                <span style={{ color: task.profitRate && parseFloat(task.profitRate) >= 0 ? '#52c41a' : '#ff4d4f' }}>
                  {task.profitRate ? task.profitRate + '%' : '-'}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.backtestDays')}>
                {task.backtestDays} {t('common.day')}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.status')}>
                <Tag color={getStatusColor(task.status)}>{getStatusText(task.status)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.progress')}>
                {task.progress}%
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.totalTrades')}>
                {task.totalTrades}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.startTime')}>
                {new Date(task.startTime).toLocaleString()}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.endTime')}>
                {task.endTime ? new Date(task.endTime).toLocaleString() : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('backtest.createdAt')}>
                {new Date(task.createdAt).toLocaleString()}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {/* 统计信息 */}
          {statistics && (
            <Row gutter={16}>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.buyTrades')}
                    value={statistics.buyTrades}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.sellTrades')}
                    value={statistics.sellTrades}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.winTrades')}
                    value={statistics.winTrades}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.lossTrades')}
                    value={statistics.lossTrades}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.winRate')}
                    value={statistics.winRate}
                    suffix="%"
                    valueStyle={{ color: parseFloat(statistics.winRate) >= 50 ? '#52c41a' : '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxProfit')}
                    value={formatUSDC(statistics.maxProfit)}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxLoss')}
                    value={formatUSDC(statistics.maxLoss)}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card>
                  <Statistic
                    title={t('backtest.maxDrawdown')}
                    value={formatUSDC(statistics.maxDrawdown)}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              {statistics.avgHoldingTime && (
                <Col span={6}>
                  <Card>
                    <Statistic
                      title={t('backtest.avgHoldingTime')}
                      value={(statistics.avgHoldingTime / 1000 / 60).toFixed(2)}
                      suffix=" min"
                    />
                  </Card>
                </Col>
              )}
            </Row>
          )}

          {/* 资金变化图表 */}
          {trades.length > 0 && (
            <Card title={t('backtest.balanceChart')}>
              <BacktestChart trades={trades} />
            </Card>
          )}

          {/* 交易记录 */}
          <Card title={t('backtest.tradeRecords')}>
            <Table
              columns={columns}
              dataSource={trades}
              rowKey="id"
              loading={tradesLoading}
              pagination={{
                current: tradesPage,
                pageSize: tradesSize,
                total: tradesTotal,
                showSizeChanger: false,
                showTotal: (total) => `${t('common.total')} ${total} ${t('common.items')}`,
                onChange: (newPage) => {
                  setTradesPage(newPage)
                  fetchTrades(newPage)
                }
              }}
              scroll={{ x: 1800 }}
            />
          </Card>
        </Space>
      </Card>
    </div>
  )
}

export default BacktestDetail

