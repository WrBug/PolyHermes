import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Dropdown, Divider, Spin } from 'antd'
import { PlusOutlined, DeleteOutlined, BarChartOutlined, UnorderedListOutlined, ArrowUpOutlined, ArrowDownOutlined, EditOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { MenuProps } from 'antd'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CopyTrading, Leader, CopyTradingStatistics } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'
import CopyTradingOrdersModal from './CopyTradingOrders/index'
import StatisticsModal from './CopyTradingOrders/StatisticsModal'
import FilteredOrdersModal from './CopyTradingOrders/FilteredOrdersModal'
import EditModal from './CopyTradingOrders/EditModal'

const { Option } = Select

const CopyTradingList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [copyTradings, setCopyTradings] = useState<CopyTrading[]>([])
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [loading, setLoading] = useState(false)
  const [statisticsMap, setStatisticsMap] = useState<Record<number, CopyTradingStatistics>>({})
  const [loadingStatistics, setLoadingStatistics] = useState<Set<number>>(new Set())
  const [filters, setFilters] = useState<{
    accountId?: number
    leaderId?: number
    enabled?: boolean
  }>({})
  
  // Modal 状态
  const [ordersModalOpen, setOrdersModalOpen] = useState(false)
  const [ordersModalCopyTradingId, setOrdersModalCopyTradingId] = useState<string>('')
  const [ordersModalTab, setOrdersModalTab] = useState<'buy' | 'sell' | 'matched'>('buy')
  const [statisticsModalOpen, setStatisticsModalOpen] = useState(false)
  const [statisticsModalCopyTradingId, setStatisticsModalCopyTradingId] = useState<string>('')
  const [filteredOrdersModalOpen, setFilteredOrdersModalOpen] = useState(false)
  const [filteredOrdersModalCopyTradingId, setFilteredOrdersModalCopyTradingId] = useState<string>('')
  const [editModalOpen, setEditModalOpen] = useState(false)
  const [editModalCopyTradingId, setEditModalCopyTradingId] = useState<string>('')
  
  useEffect(() => {
    fetchAccounts()
    fetchLeaders()
    fetchCopyTradings()
  }, [])
  
  useEffect(() => {
    fetchCopyTradings()
  }, [filters])
  
  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      console.error('获取 Leader 列表失败:', error)
    }
  }
  
  const fetchCopyTradings = async () => {
    setLoading(true)
    try {
      const response = await apiService.copyTrading.list(filters)
      if (response.data.code === 0 && response.data.data) {
        const list = response.data.data.list || []
        setCopyTradings(list)
        // 为每个跟单关系获取统计信息
        list.forEach((ct: CopyTrading) => {
          fetchStatistics(ct.id)
        })
      } else {
        message.error(response.data.msg || t('copyTradingList.fetchFailed') || '获取跟单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.fetchFailed') || '获取跟单列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const fetchStatistics = async (copyTradingId: number) => {
    // 如果正在加载或已有数据，跳过
    if (loadingStatistics.has(copyTradingId) || statisticsMap[copyTradingId]) {
      return
    }
    
    setLoadingStatistics(prev => new Set(prev).add(copyTradingId))
    try {
      const response = await apiService.statistics.detail({ copyTradingId })
      if (response.data.code === 0 && response.data.data) {
        setStatisticsMap(prev => ({
          ...prev,
          [copyTradingId]: response.data.data
        }))
      }
    } catch (error: any) {
      console.error(`获取跟单统计失败: copyTradingId=${copyTradingId}`, error)
    } finally {
      setLoadingStatistics(prev => {
        const next = new Set(prev)
        next.delete(copyTradingId)
        return next
      })
    }
  }
  
  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const getPnlIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }
  
  const formatPercent = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '-'
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }
  
  const handleToggleStatus = async (copyTrading: CopyTrading) => {
    try {
      const response = await apiService.copyTrading.updateStatus({
        copyTradingId: copyTrading.id,
        enabled: !copyTrading.enabled
      })
      if (response.data.code === 0) {
        message.success(copyTrading.enabled ? (t('copyTradingList.stopSuccess') || '停止跟单成功') : (t('copyTradingList.startSuccess') || '开启跟单成功'))
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || t('copyTradingList.updateStatusFailed') || '更新跟单状态失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.updateStatusFailed') || '更新跟单状态失败')
    }
  }
  
  const handleDelete = async (copyTradingId: number) => {
    try {
      const response = await apiService.copyTrading.delete({ copyTradingId })
      if (response.data.code === 0) {
        message.success(t('copyTradingList.deleteSuccess') || '删除跟单成功')
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || t('copyTradingList.deleteFailed') || '删除跟单失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingList.deleteFailed') || '删除跟单失败')
    }
  }
  
  const columns = [
    {
      title: t('copyTradingList.configName') || '配置名',
      key: 'configName',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
          {record.configName || t('copyTradingList.configNameNotProvided') || '未提供'}
        </div>
      )
    },
    {
      title: t('copyTradingList.wallet') || '钱包',
      key: 'account',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.accountName || `${t('copyTradingList.account') || '账户'} ${record.accountId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.walletAddress.slice(0, 4)}...${record.walletAddress.slice(-3)}`
              : `${record.walletAddress.slice(0, 6)}...${record.walletAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: t('copyTradingList.copyMode') || '跟单模式',
      key: 'copyMode',
      width: isMobile ? 100 : 120,
      render: (_: any, record: CopyTrading) => (
        <Tag color={record.copyMode === 'RATIO' ? 'blue' : 'green'}>
          {record.copyMode === 'RATIO' 
            ? `${t('copyTradingList.ratioMode') || '比例'} ${record.copyRatio}x`
            : `${t('copyTradingList.fixedAmountMode') || '固定'} ${formatUSDC(record.fixedAmount || '0')}`
          }
        </Tag>
      )
    },
    {
      title: t('copyTradingList.leader') || 'Leader',
      key: 'leader',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.leaderName || `Leader ${record.leaderId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.leaderAddress.slice(0, 4)}...${record.leaderAddress.slice(-3)}`
              : `${record.leaderAddress.slice(0, 6)}...${record.leaderAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: t('common.status') || '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: isMobile ? 80 : 100,
      render: (enabled: boolean, record: CopyTrading) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggleStatus(record)}
          checkedChildren={t('copyTradingList.enabled') || '开启'}
          unCheckedChildren={t('copyTradingList.disabled') || '停止'}
        />
      )
    },
    {
      title: t('copyTradingList.totalPnl') || '总盈亏',
      key: 'totalPnl',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => {
        const stats = statisticsMap[record.id]
        if (!stats) {
          return loadingStatistics.has(record.id) ? (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>{t('common.loading') || '加载中...'}</span>
          ) : (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>-</span>
          )
        }
        return (
          <div>
            <div style={{ 
              color: getPnlColor(stats.totalPnl), 
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              fontSize: isMobile ? 12 : 14
            }}>
              {getPnlIcon(stats.totalPnl)}
              {isMobile ? formatUSDC(stats.totalPnl) : `${formatUSDC(stats.totalPnl)} USDC`}
            </div>
            {!isMobile && (
              <div style={{ 
                fontSize: 12, 
                color: getPnlColor(stats.totalPnlPercent),
                marginTop: 4
              }}>
                {formatPercent(stats.totalPnlPercent)}
              </div>
            )}
          </div>
        )
      }
    },
    {
      title: t('common.actions') || '操作',
      key: 'action',
      width: isMobile ? 100 : 200,
      fixed: 'right' as const,
      render: (_: any, record: CopyTrading) => {
        const menuItems: MenuProps['items'] = [
          {
            key: 'matchedOrders',
            label: t('copyTradingList.matchedOrders') || '已成交订单',
            icon: <UnorderedListOutlined />,
            onClick: () => {
              setOrdersModalCopyTradingId(record.id.toString())
              setOrdersModalTab('buy')
              setOrdersModalOpen(true)
            }
          },
          {
            key: 'filteredOrders',
            label: t('copyTradingList.filteredOrders') || '已过滤订单',
            icon: <UnorderedListOutlined />,
            onClick: () => {
              setFilteredOrdersModalCopyTradingId(record.id.toString())
              setFilteredOrdersModalOpen(true)
            }
          }
        ]
        
        return (
          <Space size={isMobile ? 'small' : 'middle'} wrap>
            {!isMobile && (
              <>
                <Button
                  type="link"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setEditModalCopyTradingId(record.id.toString())
                    setEditModalOpen(true)
                  }}
                >
                  {t('common.edit') || '编辑'}
                </Button>
                <Button
                  type="link"
                  size="small"
                  icon={<BarChartOutlined />}
                  onClick={() => {
                    setStatisticsModalCopyTradingId(record.id.toString())
                    setStatisticsModalOpen(true)
                  }}
                >
                  {t('copyTradingList.statistics') || '统计'}
                </Button>
              </>
            )}
            <Dropdown menu={{ items: menuItems }} trigger={['click']}>
              <Button
                type="link"
                size="small"
                icon={<UnorderedListOutlined />}
              >
                {isMobile ? '' : (t('copyTradingList.orders') || '订单')}
              </Button>
            </Dropdown>
            {!isMobile && (
              <Popconfirm
                title={t('copyTradingList.deleteConfirm') || '确定要删除这个跟单关系吗？'}
                onConfirm={() => handleDelete(record.id)}
                okText={t('common.confirm') || '确定'}
                cancelText={t('common.cancel') || '取消'}
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                >
                  {t('common.delete') || '删除'}
                </Button>
              </Popconfirm>
            )}
          </Space>
        )
      }
    }
  ]
  
  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <h2 style={{ margin: 0 }}>{t('copyTradingList.title') || '跟单配置管理'}</h2>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/copy-trading/add')}
          >
            {t('copyTradingList.addCopyTrading') || '新增跟单'}
          </Button>
        </div>
        
        <div style={{ marginBottom: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          <Select
            placeholder={t('copyTradingList.filterWallet') || '筛选钱包'}
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.accountId}
            onChange={(value) => setFilters({ ...filters, accountId: value || undefined })}
          >
            {accounts.map(account => (
              <Option key={account.id} value={account.id}>
                {account.accountName || `${t('copyTradingList.account') || '账户'} ${account.id}`}
              </Option>
            ))}
          </Select>
          
          <Select
            placeholder={t('copyTradingList.filterLeader') || '筛选 Leader'}
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.leaderId}
            onChange={(value) => setFilters({ ...filters, leaderId: value || undefined })}
          >
            {leaders.map(leader => (
              <Option key={leader.id} value={leader.id}>
                {leader.leaderName || `Leader ${leader.id}`}
              </Option>
            ))}
          </Select>
          
          <Select
            placeholder="筛选状态"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.enabled}
            onChange={(value) => setFilters({ ...filters, enabled: value !== undefined ? value : undefined })}
          >
            <Option value={true}>开启</Option>
            <Option value={false}>停止</Option>
          </Select>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : copyTradings.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
                暂无跟单配置
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {copyTradings.map((record) => {
                  const stats = statisticsMap[record.id]
                  const date = new Date(record.createdAt)
                  const formattedDate = date.toLocaleString('zh-CN', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                  })
                  
                  return (
                    <Card
                      key={record.id}
                      style={{
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8'
                      }}
                      bodyStyle={{ padding: '16px' }}
                    >
                      {/* 基本信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ 
                          fontSize: '18px', 
                          fontWeight: 'bold', 
                          marginBottom: '8px',
                          color: '#1890ff'
                        }}>
                          {record.configName || t('copyTradingList.configNameNotProvided') || '未提供'}
                        </div>
                        <div style={{ 
                          fontSize: '14px', 
                          marginBottom: '8px',
                          color: '#666'
                        }}>
                          {record.copyMode === 'RATIO' 
                            ? `${t('copyTradingList.ratioMode') || '比例'} ${record.copyRatio}x`
                            : `${t('copyTradingList.fixedAmountMode') || '固定'} ${formatUSDC(record.fixedAmount || '0')}`
                          }
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center', justifyContent: 'space-between' }}>
                          <Tag color={record.enabled ? 'green' : 'red'}>
                            {record.enabled ? '启用' : '禁用'}
                          </Tag>
                          <Switch
                            checked={record.enabled}
                            onChange={() => handleToggleStatus(record)}
                            checkedChildren="开启"
                            unCheckedChildren="停止"
                            size="small"
                          />
                        </div>
                      </div>
                      
                      <Divider style={{ margin: '12px 0' }} />
                      
                      {/* 账户信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>账户</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {record.accountName || `账户 ${record.accountId}`}
                        </div>
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '2px' }}>
                          {record.walletAddress.slice(0, 6)}...{record.walletAddress.slice(-4)}
                        </div>
                      </div>
                      
                      {/* Leader 信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>Leader</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {record.leaderName || `Leader ${record.leaderId}`}
                        </div>
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '2px' }}>
                          {record.leaderAddress.slice(0, 6)}...{record.leaderAddress.slice(-4)}
                        </div>
                      </div>
                      
                      {/* 总盈亏 */}
                      {stats && (
                        <div style={{ marginBottom: '12px' }}>
                          <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>总盈亏</div>
                          <div style={{ 
                            fontSize: '16px', 
                            fontWeight: 'bold',
                            color: getPnlColor(stats.totalPnl),
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px'
                          }}>
                            {getPnlIcon(stats.totalPnl)}
                            {formatUSDC(stats.totalPnl)} USDC
                          </div>
                          <div style={{ 
                            fontSize: '12px', 
                            color: getPnlColor(stats.totalPnlPercent),
                            marginTop: '4px'
                          }}>
                            {formatPercent(stats.totalPnlPercent)}
                          </div>
                        </div>
                      )}
                      
                      {loadingStatistics.has(record.id) && (
                        <div style={{ marginBottom: '12px', fontSize: '12px', color: '#999' }}>
                          加载统计中...
                        </div>
                      )}
                      
                      {/* 创建时间 */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#999' }}>
                          创建时间: {formattedDate}
                        </div>
                      </div>
                      
                      {/* 操作按钮 */}
                      <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                        <Button
                          type="primary"
                          size="small"
                          icon={<EditOutlined />}
                          onClick={() => {
                            setEditModalCopyTradingId(record.id.toString())
                            setEditModalOpen(true)
                          }}
                          style={{ flex: 1, minWidth: '80px' }}
                        >
                          {t('common.edit') || '编辑'}
                        </Button>
                        <Button
                          size="small"
                          icon={<BarChartOutlined />}
                          onClick={() => {
                            setStatisticsModalCopyTradingId(record.id.toString())
                            setStatisticsModalOpen(true)
                          }}
                          style={{ flex: 1, minWidth: '80px' }}
                        >
                          {t('copyTradingList.statistics') || '统计'}
                        </Button>
                        <Dropdown 
                          menu={{ 
                            items: [
                              {
                                key: 'matchedOrders',
                                label: t('copyTradingList.matchedOrders') || '已成交订单',
                                icon: <UnorderedListOutlined />,
                                onClick: () => {
                                  setOrdersModalCopyTradingId(record.id.toString())
                                  setOrdersModalTab('buy')
                                  setOrdersModalOpen(true)
                                }
                              },
                              {
                                key: 'filteredOrders',
                                label: t('copyTradingList.filteredOrders') || '已过滤订单',
                                icon: <UnorderedListOutlined />,
                                onClick: () => {
                                  setFilteredOrdersModalCopyTradingId(record.id.toString())
                                  setFilteredOrdersModalOpen(true)
                                }
                              }
                            ]
                          }} 
                          trigger={['click']}
                        >
                          <Button
                            size="small"
                            icon={<UnorderedListOutlined />}
                            style={{ flex: 1, minWidth: '80px' }}
                          >
                            {t('copyTradingList.orders') || '订单'}
                          </Button>
                        </Dropdown>
                        <Popconfirm
                          title={t('copyTradingList.deleteConfirm') || '确定要删除这个跟单关系吗？'}
                          onConfirm={() => handleDelete(record.id)}
                          okText={t('common.confirm') || '确定'}
                          cancelText={t('common.cancel') || '取消'}
                        >
                          <Button
                            danger
                            size="small"
                            icon={<DeleteOutlined />}
                            style={{ flex: 1, minWidth: '80px' }}
                          >
                            {t('common.delete') || '删除'}
                          </Button>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={copyTradings}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`
            }}
          />
        )}
      </Card>
      
      {/* Modal 组件 */}
      <CopyTradingOrdersModal
        open={ordersModalOpen}
        onClose={() => setOrdersModalOpen(false)}
        copyTradingId={ordersModalCopyTradingId}
        defaultTab={ordersModalTab}
      />
      <StatisticsModal
        open={statisticsModalOpen}
        onClose={() => setStatisticsModalOpen(false)}
        copyTradingId={statisticsModalCopyTradingId}
      />
      <FilteredOrdersModal
        open={filteredOrdersModalOpen}
        onClose={() => setFilteredOrdersModalOpen(false)}
        copyTradingId={filteredOrdersModalCopyTradingId}
      />
      <EditModal
        open={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        copyTradingId={editModalCopyTradingId}
        onSuccess={() => {
          fetchCopyTradings()
        }}
      />
    </div>
  )
}

export default CopyTradingList

