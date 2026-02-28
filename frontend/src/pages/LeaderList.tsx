import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message, List, Empty, Spin, Divider, Typography, Modal, Descriptions, Statistic, Row, Col, Tooltip, Badge } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, GlobalOutlined, EyeOutlined, ReloadOutlined, WalletOutlined, CopyOutlined, LineChartOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { Leader, LeaderBalanceResponse } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Text } = Typography

const LeaderList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [loading, setLoading] = useState(false)
  const [balanceMap, setBalanceMap] = useState<Record<number, { total: string; available: string; position: string }>>({})
  const [balanceLoading, setBalanceLoading] = useState<Record<number, boolean>>({})

  // 详情 Modal
  const [detailModalVisible, setDetailModalVisible] = useState(false)
  const [detailLeader, setDetailLeader] = useState<Leader | null>(null)
  const [detailBalance, setDetailBalance] = useState<LeaderBalanceResponse | null>(null)
  const [detailBalanceLoading, setDetailBalanceLoading] = useState(false)

  useEffect(() => {
    fetchLeaders()
  }, [])

  const fetchLeaders = async () => {
    setLoading(true)
    try {
      const response = await apiService.leaders.list()
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      } else {
        message.error(response.data.msg || t('leaderList.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderList.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  // 加载所有 Leader 的余额
  useEffect(() => {
    const loadBalances = async () => {
      for (const leader of leaders) {
        if (!balanceMap[leader.id] && !balanceLoading[leader.id]) {
          setBalanceLoading(prev => ({ ...prev, [leader.id]: true }))
          try {
            const balanceData = await apiService.leaders.balance({ leaderId: leader.id })
            if (balanceData.data.code === 0 && balanceData.data.data) {
              setBalanceMap(prev => ({
                ...prev,
                [leader.id]: {
                  total: balanceData.data.data.totalBalance || '0',
                  available: balanceData.data.data.availableBalance || '0',
                  position: balanceData.data.data.positionBalance || '0'
                }
              }))
            }
          } catch (error) {
            console.error(`获取 Leader ${leader.id} 余额失败:`, error)
            setBalanceMap(prev => ({
              ...prev,
              [leader.id]: { total: '-', available: '-', position: '-' }
            }))
          } finally {
            setBalanceLoading(prev => ({ ...prev, [leader.id]: false }))
          }
        }
      }
    }

    if (leaders.length > 0) {
      loadBalances()
    }
  }, [leaders])

  const handleDelete = async (leaderId: number) => {
    try {
      const response = await apiService.leaders.delete({ leaderId })
      if (response.data.code === 0) {
        message.success(t('leaderList.deleteSuccess'))
        fetchLeaders()
      } else {
        message.error(response.data.msg || t('leaderList.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderList.deleteFailed'))
    }
  }

  const handleShowDetail = async (leader: Leader) => {
    try {
      setDetailModalVisible(true)
      setDetailLeader(leader)
      setDetailBalance(null)
      setDetailBalanceLoading(false)

      // 加载详情和余额
      try {
        const leaderDetail = await apiService.leaders.detail({ leaderId: leader.id })
        if (leaderDetail.data.code === 0 && leaderDetail.data.data) {
          setDetailLeader(leaderDetail.data.data)
        }

        // 加载余额
        setDetailBalanceLoading(true)
        try {
          const balanceData = await apiService.leaders.balance({ leaderId: leader.id })
          if (balanceData.data.code === 0 && balanceData.data.data) {
            setDetailBalance(balanceData.data.data)
          }
        } catch (error) {
          console.error('获取余额失败:', error)
          setDetailBalance(null)
        } finally {
          setDetailBalanceLoading(false)
        }
      } catch (error: any) {
        console.error('获取 Leader 详情失败:', error)
        message.error(error.message || t('leaderList.fetchFailed'))
        setDetailModalVisible(false)
        setDetailLeader(null)
      }
    } catch (error: any) {
      console.error('打开详情失败:', error)
      message.error(error.message || t('leaderList.openDetailFailed'))
      setDetailModalVisible(false)
      setDetailLeader(null)
    }
  }

  const handleRefreshDetailBalance = async () => {
    if (!detailLeader) return

    setDetailBalanceLoading(true)
    try {
      const balanceData = await apiService.leaders.balance({ leaderId: detailLeader.id })
      if (balanceData.data.code === 0 && balanceData.data.data) {
        setDetailBalance(balanceData.data.data)
        message.success(t('leaderDetail.refresh'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDetail.fetchBalanceFailed'))
    } finally {
      setDetailBalanceLoading(false)
    }
  }

  const formatTimestamp = (timestamp: number) => {
    const date = new Date(timestamp)
    return date.toLocaleString(i18n.language || 'zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  const getPositionColumns = () => {
    return [
      {
        title: t('leaderDetail.market'),
        dataIndex: 'title',
        key: 'title',
        render: (title: string) => {
          if (!title) return <Text type="secondary">-</Text>
          const displayText = isMobile && title.length > 20 ? `${title.slice(0, 20)}...` : title
          return <Text style={{ fontSize: isMobile ? '12px' : '13px' }}>{displayText}</Text>
        }
      },
      {
        title: t('leaderDetail.side'),
        dataIndex: 'side',
        key: 'side',
        render: (side: string) => {
          const color = side === 'YES' ? 'green' : 'red'
          return <Tag color={color}>{side}</Tag>
        }
      },
      {
        title: t('leaderDetail.quantity'),
        dataIndex: 'quantity',
        key: 'quantity',
        render: (quantity: string) => formatUSDC(quantity)
      },
      {
        title: t('leaderDetail.avgPrice'),
        dataIndex: 'avgPrice',
        key: 'avgPrice',
        render: (price: string) => formatUSDC(price)
      },
      {
        title: t('leaderDetail.currentValue'),
        dataIndex: 'currentValue',
        key: 'currentValue',
        render: (value: string) => formatUSDC(value)
      },
      {
        title: t('leaderDetail.pnl'),
        dataIndex: 'pnl',
        key: 'pnl',
        render: (pnl: string | undefined) => {
          if (!pnl || pnl === '0') {
            return <Text type="secondary">-</Text>
          } else {
            const numPnl = parseFloat(pnl)
            const color = numPnl > 0 ? '#52c41a' : '#ff4d4f'
            return <Text style={{ color }}>{formatUSDC(pnl)}</Text>
          }
        }
      }
    ]
  }

  const columns = [
    {
      title: t('leaderList.leaderName'),
      dataIndex: 'leaderName',
      key: 'leaderName',
      width: 200,
      render: (text: string, record: Leader) => (
        <Space direction="vertical" size={0}>
          <Text strong style={{ fontSize: '14px' }}>{text || `Leader ${record.id}`}</Text>
          <Text type="secondary" style={{ fontSize: '12px', fontFamily: 'monospace' }}>{record.leaderAddress}</Text>
        </Space>
      )
    },
    {
      title: t('leaderList.remark'),
      dataIndex: 'remark',
      key: 'remark',
      width: 180,
      ellipsis: true,
      render: (remark: string | undefined) => {
        if (!remark) return <Text type="secondary">-</Text>
        return <Text ellipsis={{ tooltip: remark }} style={{ maxWidth: 160 }}>{remark}</Text>
      }
    },
    {
      title: t('leaderDetail.availableBalance'),
      key: 'balance',
      width: 180,
      render: (_: any, record: Leader) => {
        const balance = balanceMap[record.id]
        if (!balance) return <Spin size="small" />
        return (
          <Space direction="vertical" size={0}>
            <Text style={{ color: '#52c41a', fontSize: '14px', fontWeight: '500' }}>
              {balance.available === '-' ? '-' : `${formatUSDC(balance.available)} USDC`}
            </Text>
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {t('leaderDetail.positionBalance')}: {formatUSDC(balance.position)}
            </Text>
          </Space>
        )
      }
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: 200,
      fixed: 'right' as const,
      render: (_: any, record: Leader) => (
        <Space size={4}>
          <Tooltip title={t('common.viewDetail')}>
            <div
              onClick={() => handleShowDetail(record)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <EyeOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          {record.website && (
            <Tooltip title={t('leaderList.openWebsite')}>
              <div
                onClick={() => window.open(record.website, '_blank', 'noopener,noreferrer')}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
              >
                <GlobalOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
              </div>
            </Tooltip>
          )}

          <Tooltip title={t('common.edit')}>
            <div
              onClick={() => navigate(`/leaders/edit?id=${record.id}`)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <EditOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Tooltip title={`${t('leaderList.viewCopyTradings')} (${record.copyTradingCount})`}>
            <div
              onClick={() => {
                if (record.copyTradingCount > 0) {
                  navigate(`/copy-trading?leaderId=${record.id}`)
                }
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: record.copyTradingCount === 0 ? 'not-allowed' : 'pointer',
                borderRadius: '6px',
                opacity: record.copyTradingCount === 0 ? 0.4 : 1,
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => record.copyTradingCount > 0 && (e.currentTarget.style.backgroundColor = '#f0f0f0')}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <Badge count={record.copyTradingCount} size="small" offset={[-4, -4]}>
                <CopyOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
              </Badge>
            </div>
          </Tooltip>

          <Tooltip title={`${t('leaderList.viewBacktests')} (${record.backtestCount})`}>
            <div
              onClick={() => {
                if (record.backtestCount > 0) {
                  navigate(`/backtest?leaderId=${record.id}`)
                }
              }}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: record.backtestCount === 0 ? 'not-allowed' : 'pointer',
                borderRadius: '6px',
                opacity: record.backtestCount === 0 ? 0.4 : 1,
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => record.backtestCount > 0 && (e.currentTarget.style.backgroundColor = '#f0f0f0')}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <Badge count={record.backtestCount} size="small" offset={[-4, -4]}>
                <LineChartOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
              </Badge>
            </div>
          </Tooltip>

          <Popconfirm
            title={t('leaderList.deleteConfirm')}
            description={record.copyTradingCount > 0 ? t('leaderList.deleteConfirmDesc', { count: record.copyTradingCount }) : undefined}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#fff1f0'}
                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
              >
                <DeleteOutlined style={{ fontSize: '16px', color: '#ff4d4f' }} />
              </div>
            </Tooltip>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('leaderList.title')}</h2>
        <Tooltip title={t('leaderList.addLeader')}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/leaders/add')} size={isMobile ? 'middle' : 'large'} style={{ borderRadius: '8px', height: isMobile ? '40px' : '48px', fontSize: isMobile ? '14px' : '16px' }} />
        </Tooltip>
      </div>

      <Card style={{ borderRadius: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', border: '1px solid #e8e8e8' }} bodyStyle={{ padding: isMobile ? '12px' : '24px' }}>
        {isMobile ? (
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : leaders.length === 0 ? (
              <Empty description={t('leaderList.noData')} />
            ) : (
              <List
                dataSource={leaders}
                renderItem={(leader) => {
                  const balance = balanceMap[leader.id]

                  return (
                    <Card
                      key={leader.id}
                      style={{
                        marginBottom: '10px',
                        borderRadius: '10px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8',
                        overflow: 'hidden'
                      }}
                      bodyStyle={{ padding: '0' }}
                    >
                      {/* 头部区域 - 名称和地址 */}
                      <div style={{
                        padding: '10px 12px',
                        background: 'var(--ant-color-primary, #1677ff)',
                        color: '#fff'
                      }}>
                        <div style={{ fontSize: '15px', fontWeight: '600', marginBottom: '2px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                          <span>{leader.leaderName || `Leader ${leader.id}`}</span>
                          {leader.website && (
                            <GlobalOutlined
                              style={{ fontSize: '13px', cursor: 'pointer', opacity: 0.8 }}
                              onClick={() => window.open(leader.website, '_blank', 'noopener,noreferrer')}
                            />
                          )}
                        </div>
                        <div style={{ fontSize: '10px', opacity: '0.85', fontFamily: 'monospace', wordBreak: 'break-all' }}>
                          {leader.leaderAddress}
                        </div>
                      </div>

                      {/* 资产区域 - 常驻显示 */}
                      <div style={{
                        padding: '8px 12px',
                        backgroundColor: '#fafafa',
                        borderBottom: '1px solid #f0f0f0',
                        minHeight: '42px',
                        display: 'flex',
                        alignItems: 'center'
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                          <div>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('leaderDetail.availableBalance')}
                            </div>
                            <div style={{ fontSize: '14px', fontWeight: '600', color: '#52c41a' }}>
                              {balance?.available && balance.available !== '-' ? `${formatUSDC(balance.available)} USDC` : '- USDC'}
                            </div>
                          </div>
                          <div style={{ textAlign: 'right' }}>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('leaderDetail.positionBalance')}
                            </div>
                            <div style={{ fontSize: '14px', fontWeight: '500', color: '#722ed1' }}>
                              {balance?.position && balance.position !== '-' ? formatUSDC(balance.position) : '-'}
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* 备注区域 */}
                      {leader.remark && (
                        <div style={{
                          padding: '6px 12px',
                          backgroundColor: '#fffbe6',
                          borderBottom: '1px solid #ffe58f',
                          fontSize: '11px',
                          color: '#8c8c8c'
                        }}>
                          <span style={{ color: '#d48806' }}>{t('leaderList.remark')}：</span>
                          <span>{leader.remark}</span>
                        </div>
                      )}

                      {/* 图标操作栏 */}
                      <div style={{
                        padding: '8px 12px',
                        display: 'flex',
                        justifyContent: 'space-around',
                        alignItems: 'center'
                      }}>
                        <Tooltip title={t('common.viewDetail')}>
                          <div
                            onClick={() => handleShowDetail(leader)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <EyeOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.viewDetail')}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('common.edit')}>
                          <div
                            onClick={() => navigate(`/leaders/edit?id=${leader.id}`)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <EditOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.edit')}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('leaderList.viewCopyTradings')}>
                          <div
                            onClick={() => navigate(`/copy-trading?leaderId=${leader.id}`)}
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              cursor: leader.copyTradingCount === 0 ? 'not-allowed' : 'pointer',
                              padding: '4px 8px',
                              opacity: leader.copyTradingCount === 0 ? 0.4 : 1
                            }}
                          >
                            <Badge count={leader.copyTradingCount} size="small" offset={[-2, -2]}>
                              <CopyOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            </Badge>
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('leaderList.viewCopyTradings')}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('leaderList.viewBacktests')}>
                          <div
                            onClick={() => navigate(`/backtest?leaderId=${leader.id}`)}
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              cursor: leader.backtestCount === 0 ? 'not-allowed' : 'pointer',
                              padding: '4px 8px',
                              opacity: leader.backtestCount === 0 ? 0.4 : 1
                            }}
                          >
                            <Badge count={leader.backtestCount} size="small" offset={[-2, -2]}>
                              <LineChartOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            </Badge>
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('leaderList.viewBacktests')}</span>
                          </div>
                        </Tooltip>

                        <Popconfirm
                          title={t('leaderList.deleteConfirm')}
                          description={leader.copyTradingCount > 0 ? t('leaderList.deleteConfirmDesc', { count: leader.copyTradingCount }) : undefined}
                          onConfirm={() => handleDelete(leader.id)}
                          okText={t('common.confirm')}
                          cancelText={t('common.cancel')}
                        >
                          <Tooltip title={t('common.delete')}>
                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                              <DeleteOutlined style={{ fontSize: '18px', color: '#ff4d4f' }} />
                              <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.delete')}</span>
                            </div>
                          </Tooltip>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                }}
              />
            )}
          </div>
        ) : (
          <Table
            dataSource={leaders}
            columns={columns}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
            size="large"
            style={{ fontSize: '14px' }}
          />
        )}
      </Card>

      {/* 详情 Modal */}
      <Modal
        title={
          <Space>
            <WalletOutlined />
            <span>{t('leaderDetail.title')}</span>
          </Space>
        }
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setDetailModalVisible(false)}>{t('common.close')}</Button>
        ]}
        width={isMobile ? '95%' : 1000}
        style={{ top: 20 }}
      >
        {!detailLeader ? (
          <div style={{ textAlign: 'center', padding: '40px' }}>
            <Spin size="large" />
          </div>
        ) : (
          <>
            {/* 基本信息 */}
            <Descriptions
              title={
                <Space>
                  <WalletOutlined />
                  <span style={{ fontSize: '16px', fontWeight: 'bold' }}>{t('leaderDetail.basicInfo')}</span>
                </Space>
              }
              bordered
              column={isMobile ? 1 : 2}
              size={isMobile ? 'small' : 'default'}
            >
              <Descriptions.Item label={t('leaderDetail.leaderName')}>
                {detailLeader.leaderName || `Leader ${detailLeader.id}`}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.leaderAddress')}>
                <span style={{ fontFamily: 'monospace' }}>{detailLeader.leaderAddress}</span>
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.copyTradingCount')}>
                <Tag color="cyan">{detailLeader.copyTradingCount || 0}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.remark')}>
                {detailLeader.remark || <Text type="secondary">-</Text>}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.updatedAt')}>
                {formatTimestamp(detailLeader.updatedAt)}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderDetail.website')}>
                {detailLeader.website ? (
                  <Button type="link" icon={<GlobalOutlined />} onClick={() => window.open(detailLeader.website, '_blank', 'noopener,noreferrer')} style={{ padding: 0 }}>
                    {t('leaderDetail.openWebsite')}
                  </Button>
                ) : <Text type="secondary">-</Text>}
              </Descriptions.Item>
            </Descriptions>

            <Divider />

            {/* 余额信息 */}
            <div style={{ marginBottom: '16px' }}>
              <Space>
                <WalletOutlined />
                <span style={{ fontSize: '16px', fontWeight: 'bold' }}>{t('leaderDetail.balanceInfo')}</span>
                <Button type="text" size="small" icon={<ReloadOutlined />} onClick={handleRefreshDetailBalance} loading={detailBalanceLoading}>
                  {t('leaderDetail.refresh')}
                </Button>
              </Space>
            </div>

            {detailBalanceLoading && !detailBalance ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin />
              </div>
            ) : detailBalance ? (
              <>
                <Row gutter={16} style={{ marginBottom: '16px' }}>
                  <Col xs={24} sm={8} md={6}>
                    <Card bordered={false} style={{ backgroundColor: '#f5f5f5', borderRadius: '8px' }}>
                      <Statistic
                        title={t('leaderDetail.availableBalance')}
                        value={parseFloat(detailBalance.availableBalance)}
                        precision={4}
                        valueStyle={{ color: '#1890ff' }}
                        suffix="USDC"
                        formatter={(value) => formatUSDC(value?.toString() || '0')}
                      />
                    </Card>
                  </Col>
                  <Col xs={24} sm={8} md={6}>
                    <Card bordered={false} style={{ backgroundColor: '#f5f5f5', borderRadius: '8px' }}>
                      <Statistic
                        title={t('leaderDetail.positionBalance')}
                        value={parseFloat(detailBalance.positionBalance)}
                        precision={4}
                        valueStyle={{ color: '#722ed1' }}
                        suffix="USDC"
                        formatter={(value) => formatUSDC(value?.toString() || '0')}
                      />
                    </Card>
                  </Col>
                  <Col xs={24} sm={8} md={6}>
                    <Card bordered={false} style={{ backgroundColor: '#f5f5f5', borderRadius: '8px' }}>
                      <Statistic
                        title={t('leaderDetail.totalBalance')}
                        value={parseFloat(detailBalance.totalBalance)}
                        precision={4}
                        valueStyle={{ color: '#52c41a', fontWeight: 'bold' }}
                        suffix="USDC"
                        formatter={(value) => formatUSDC(value?.toString() || '0')}
                      />
                    </Card>
                  </Col>
                </Row>

                {/* 持仓列表 */}
                <Divider />
                <div style={{ marginBottom: '16px' }}>
                  <Space>
                    <span style={{ fontSize: '16px', fontWeight: 'bold' }}>{t('leaderDetail.positions')}</span>
                    <Tag color="blue">{detailBalance.positions?.length || 0}</Tag>
                  </Space>
                </div>

                {detailBalance.positions && detailBalance.positions.length > 0 ? (
                  <Table
                    dataSource={detailBalance.positions}
                    columns={getPositionColumns()}
                    rowKey={(record, index) => `${record.title}-${record.side}-${index}`}
                    pagination={{ pageSize: 10, showSizeChanger: !isMobile }}
                    scroll={{ x: isMobile ? 800 : 'auto' }}
                    size={isMobile ? 'small' : 'middle'}
                  />
                ) : (
                  <Empty description={t('leaderDetail.noPositions')} />
                )}
              </>
            ) : (
              <Empty description={t('leaderDetail.noBalanceData')} />
            )}
          </>
        )}
      </Modal>
    </div>
  )
}

export default LeaderList
