import { useEffect, useRef, useState } from 'react'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Modal, Form, Input, InputNumber, Tabs, Empty, Tooltip } from 'antd'
import { PlusOutlined, EditOutlined, UnorderedListOutlined, DeleteOutlined, RightOutlined } from '@ant-design/icons'
import { useNavigate, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import {
  clearWhaleMonitorFormDraft,
  conditionIdsToMarkets,
  loadWhaleMonitorFormDraft,
  saveWhaleMonitorFormDraft,
  strategyMarketsToItems
} from '../constants/whaleMonitor'
import type {
  WhaleMonitorStrategyDto,
  WhaleMonitorTriggerDto,
  WhaleMonitorMarketItem,
  WhaleMonitorStrategyListLocationState,
  Account
} from '../types'
import { formatUSDC } from '../utils'

const WhaleMonitorStrategyList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()

  const [strategies, setStrategies] = useState<WhaleMonitorStrategyDto[]>([])
  const [loading, setLoading] = useState(false)
  const [filterAccountId, setFilterAccountId] = useState<number | undefined>()
  const [filterEnabled, setFilterEnabled] = useState<boolean | undefined>()

  const [formVisible, setFormVisible] = useState(false)
  const [editingStrategy, setEditingStrategy] = useState<WhaleMonitorStrategyDto | null>(null)
  const [form] = Form.useForm()
  const [submitting, setSubmitting] = useState(false)
  const [selectedMarkets, setSelectedMarkets] = useState<WhaleMonitorMarketItem[]>([])
  const [pendingFormDraft, setPendingFormDraft] = useState<ReturnType<typeof loadWhaleMonitorFormDraft>>(null)
  const draftRestoreStarted = useRef(false)

  const [triggerVisible, setTriggerVisible] = useState(false)
  const [triggerStrategyId, setTriggerStrategyId] = useState<number>(0)
  const [triggerRecords, setTriggerRecords] = useState<WhaleMonitorTriggerDto[]>([])
  const [triggerTotal, setTriggerTotal] = useState(0)
  const [triggerPage, setTriggerPage] = useState(1)
  const [triggerTab, setTriggerTab] = useState<string>('success')
  const [triggerLoading, setTriggerLoading] = useState(false)

  useEffect(() => {
    fetchAccounts()
  }, [fetchAccounts])

  useEffect(() => {
    fetchList()
  }, [filterAccountId, filterEnabled])

  useEffect(() => {
    if (draftRestoreStarted.current) return
    const draft = loadWhaleMonitorFormDraft()
    const state = location.state as WhaleMonitorStrategyListLocationState | null
    if (!draft && !state?.selectedMarkets) return

    draftRestoreStarted.current = true
    if (draft) clearWhaleMonitorFormDraft()
    if (state?.selectedMarkets) {
      navigate(location.pathname, { replace: true, state: null })
    }

    if (draft) {
      setPendingFormDraft({
        ...draft,
        selectedMarkets: state?.selectedMarkets ?? draft.selectedMarkets
      })
    }
  }, [location.pathname, location.state, navigate])

  useEffect(() => {
    if (!pendingFormDraft) return
    if (pendingFormDraft.editingStrategyId && loading) return
    if (pendingFormDraft.editingStrategyId) {
      const strategy = strategies.find(s => s.id === pendingFormDraft.editingStrategyId)
      if (!strategy) {
        setPendingFormDraft(null)
        return
      }
      setEditingStrategy(strategy)
    } else {
      setEditingStrategy(null)
    }
    const draftStrategy = pendingFormDraft.editingStrategyId
      ? strategies.find(s => s.id === pendingFormDraft.editingStrategyId)
      : undefined
    const knownMarkets =
      draftStrategy?.markets && draftStrategy.markets.length > 0
        ? strategyMarketsToItems(draftStrategy.markets)
        : pendingFormDraft.selectedMarkets
    setSelectedMarkets(
      conditionIdsToMarkets(
        draftStrategy?.conditionIds ?? knownMarkets.map(m => m.conditionId),
        knownMarkets
      )
    )
    form.setFieldsValue(pendingFormDraft.formValues)
    setFormVisible(true)
    setPendingFormDraft(null)
  }, [pendingFormDraft, strategies, loading, form])

  const fetchList = async () => {
    setLoading(true)
    try {
      const params: { accountId?: number; enabled?: boolean } = {}
      if (filterAccountId) params.accountId = filterAccountId
      if (filterEnabled !== undefined) params.enabled = filterEnabled
      const res = await apiService.whaleMonitorStrategy.list(params)
      if (res.data.code === 0 && res.data.data) {
        setStrategies(res.data.data.list || [])
      } else {
        message.error(t('whaleMonitorStrategy.list.fetchFailed'))
      }
    } catch (_e) {
      message.error(t('whaleMonitorStrategy.list.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const handleToggleEnabled = async (strategy: WhaleMonitorStrategyDto) => {
    try {
      const res = await apiService.whaleMonitorStrategy.update({
        strategyId: strategy.id,
        enabled: !strategy.enabled
      })
      if (res.data.code === 0) {
        message.success(strategy.enabled ? t('whaleMonitorStrategy.list.disable') : t('whaleMonitorStrategy.list.enable'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (_e) {
      message.error('Error')
    }
  }

  const handleDelete = async (strategyId: number) => {
    try {
      const res = await apiService.whaleMonitorStrategy.delete({ strategyId })
      if (res.data.code === 0) {
        message.success('OK')
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (_e) {
      message.error('Error')
    }
  }

  const goToMarketSelect = () => {
    saveWhaleMonitorFormDraft({
      formValues: form.getFieldsValue(),
      selectedMarkets,
      editingStrategyId: editingStrategy?.id
    })
    navigate('/whale-monitor-strategy/markets', {
      state: { selectedMarkets }
    })
  }

  const showCreateModal = () => {
    setEditingStrategy(null)
    setSelectedMarkets([])
    form.resetFields()
    form.setFieldsValue({
      windowSeconds: 10,
      minPrice: 0,
      maxPrice: 1,
      cooldownSeconds: 60,
      enabled: true
    })
    setFormVisible(true)
  }

  const showEditModal = (strategy: WhaleMonitorStrategyDto) => {
    setEditingStrategy(strategy)
    const known =
      strategy.markets && strategy.markets.length > 0
        ? strategyMarketsToItems(strategy.markets)
        : []
    setSelectedMarkets(conditionIdsToMarkets(strategy.conditionIds, known))
    form.setFieldsValue({
      accountId: strategy.accountId,
      name: strategy.name,
      windowSeconds: strategy.windowSeconds,
      thresholdAmount: strategy.thresholdAmount,
      orderAmount: strategy.orderAmount,
      minPrice: parseFloat(strategy.minPrice),
      maxPrice: parseFloat(strategy.maxPrice),
      cooldownSeconds: strategy.cooldownSeconds,
      enabled: strategy.enabled
    })
    setFormVisible(true)
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      setSubmitting(true)

      if (selectedMarkets.length === 0) {
        message.error(t('whaleMonitorStrategy.form.conditionIds'))
        setSubmitting(false)
        return
      }

      const payload = {
        ...values,
        conditionIds: selectedMarkets.map(m => m.conditionId),
        minPrice: String(values.minPrice ?? 0),
        maxPrice: String(values.maxPrice ?? 1),
        thresholdAmount: String(values.thresholdAmount),
        orderAmount: String(values.orderAmount)
      }

      if (editingStrategy) {
        const res = await apiService.whaleMonitorStrategy.update({
          strategyId: editingStrategy.id,
          ...payload
        })
        if (res.data.code === 0) {
          message.success('OK')
          setFormVisible(false)
          fetchList()
        } else {
          message.error(res.data.msg)
        }
      } else {
        const res = await apiService.whaleMonitorStrategy.create(payload)
        if (res.data.code === 0) {
          message.success('OK')
          setFormVisible(false)
          fetchList()
        } else {
          message.error(res.data.msg)
        }
      }
    } catch (_e) {
      // form validation error
    } finally {
      setSubmitting(false)
    }
  }

  const fetchTriggerRecords = async (strategyId: number, page: number, status?: string) => {
    setTriggerLoading(true)
    try {
      const params: { strategyId: number; page: number; pageSize: number; status?: string } = {
        strategyId,
        page,
        pageSize: 20,
      }
      if (status) params.status = status
      const res = await apiService.whaleMonitorStrategy.triggers(params)
      if (res.data.code === 0 && res.data.data) {
        setTriggerRecords(res.data.data.list || [])
        setTriggerTotal(res.data.data.total || 0)
      }
    } catch (_e) {
      // ignore
    } finally {
      setTriggerLoading(false)
    }
  }

  const showTriggerRecords = (strategyId: number) => {
    setTriggerStrategyId(strategyId)
    setTriggerPage(1)
    setTriggerTab('success')
    setTriggerVisible(true)
    fetchTriggerRecords(strategyId, 1, 'success')
  }

  const handleTriggerTabChange = (tab: string) => {
    setTriggerTab(tab)
    setTriggerPage(1)
    fetchTriggerRecords(triggerStrategyId, 1, tab === 'all' ? undefined : tab)
  }

  const getAccountName = (accountId: number): string => {
    const acc = accounts.find((a: Account) => a.id === accountId)
    if (!acc) return String(accountId)
    return acc.accountName || `${acc.walletAddress.slice(0, 6)}...${acc.walletAddress.slice(-4)}`
  }

  const formatTime = (ts: number): string => {
    if (!ts) return '-'
    return new Date(ts).toLocaleString()
  }

  const columns = [
    {
      title: t('whaleMonitorStrategy.list.strategyName'),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      render: (name: string) => name || '-'
    },
    {
      title: t('whaleMonitorStrategy.list.account'),
      dataIndex: 'accountId',
      key: 'accountId',
      render: (accountId: number) => getAccountName(accountId)
    },
    {
      title: t('whaleMonitorStrategy.list.marketCount'),
      dataIndex: 'conditionIds',
      key: 'marketCount',
      render: (ids: string[]) => (
        <Tooltip title={ids.join(', ')}>
          <Tag>{ids.length}</Tag>
        </Tooltip>
      )
    },
    {
      title: t('whaleMonitorStrategy.list.window'),
      dataIndex: 'windowSeconds',
      key: 'windowSeconds',
      render: (v: number) => `${v}${t('whaleMonitorStrategy.list.seconds')}`
    },
    {
      title: t('whaleMonitorStrategy.list.threshold'),
      dataIndex: 'thresholdAmount',
      key: 'thresholdAmount',
      render: (v: string) => `$${formatUSDC(v)}`
    },
    {
      title: t('whaleMonitorStrategy.list.orderAmount'),
      dataIndex: 'orderAmount',
      key: 'orderAmount',
      render: (v: string) => `$${formatUSDC(v)}`
    },
    {
      title: t('whaleMonitorStrategy.list.priceRange'),
      key: 'priceRange',
      render: (_: unknown, record: WhaleMonitorStrategyDto) => `${record.minPrice} ~ ${record.maxPrice}`
    },
    {
      title: t('whaleMonitorStrategy.list.cooldown'),
      dataIndex: 'cooldownSeconds',
      key: 'cooldownSeconds',
      render: (v: number) => `${v}${t('whaleMonitorStrategy.list.seconds')}`
    },
    {
      title: t('whaleMonitorStrategy.list.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean, record: WhaleMonitorStrategyDto) => (
        <Switch checked={enabled} onChange={() => handleToggleEnabled(record)} />
      )
    },
    {
      title: t('whaleMonitorStrategy.list.triggerCount'),
      dataIndex: 'triggerCount',
      key: 'triggerCount',
      render: (count: number) => count || 0
    },
    {
      title: t('whaleMonitorStrategy.list.actions'),
      key: 'actions',
      render: (_: unknown, record: WhaleMonitorStrategyDto) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => showEditModal(record)}>
            {t('whaleMonitorStrategy.list.edit')}
          </Button>
          <Button type="link" size="small" icon={<UnorderedListOutlined />} onClick={() => showTriggerRecords(record.id)}>
            {t('whaleMonitorStrategy.list.viewTriggers')}
          </Button>
          <Popconfirm title={t('whaleMonitorStrategy.list.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              {t('whaleMonitorStrategy.list.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  const triggerColumns = [
    {
      title: t('whaleMonitorStrategy.triggerRecords.triggerTime'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (ts: number) => formatTime(ts)
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.conditionId'),
      dataIndex: 'conditionId',
      key: 'conditionId',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v}>
          <span>{v.slice(0, 10)}...</span>
        </Tooltip>
      )
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.tokenId'),
      dataIndex: 'tokenId',
      key: 'tokenId',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v}>
          <span>{v.slice(0, 10)}...</span>
        </Tooltip>
      )
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.triggerVolume'),
      dataIndex: 'triggerVolume',
      key: 'triggerVolume',
      render: (v: string) => `$${formatUSDC(v)}`
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.orderPrice'),
      dataIndex: 'orderPrice',
      key: 'orderPrice',
      render: (v: string) => formatUSDC(v)
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.orderAmount'),
      dataIndex: 'orderAmount',
      key: 'orderAmount',
      render: (v: string) => `$${formatUSDC(v)}`
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.orderId'),
      dataIndex: 'orderId',
      key: 'orderId',
      ellipsis: true,
      render: (v: string) => v ? (
        <Tooltip title={v}><span>{v.slice(0, 10)}...</span></Tooltip>
      ) : '-'
    },
    {
      title: t('whaleMonitorStrategy.triggerRecords.failReason'),
      dataIndex: 'failReason',
      key: 'failReason',
      ellipsis: true,
      render: (v: string) => v ? <Tooltip title={v}><span>{v}</span></Tooltip> : '-'
    }
  ]

  const renderMobileCard = (strategy: WhaleMonitorStrategyDto) => (
    <Card key={strategy.id} size="small" style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <strong>{strategy.name || '-'}</strong>
        <Switch checked={strategy.enabled} onChange={() => handleToggleEnabled(strategy)} />
      </div>
      <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>
        {t('whaleMonitorStrategy.list.account')}: {getAccountName(strategy.accountId)}
      </div>
      <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>
        {t('whaleMonitorStrategy.list.marketCount')}: <Tag>{strategy.conditionIds.length}</Tag>
      </div>
      <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>
        {t('whaleMonitorStrategy.list.window')}: {strategy.windowSeconds}{t('whaleMonitorStrategy.list.seconds')}
        &nbsp;|&nbsp;
        {t('whaleMonitorStrategy.list.threshold')}: ${formatUSDC(strategy.thresholdAmount)}
        &nbsp;|&nbsp;
        {t('whaleMonitorStrategy.list.orderAmount')}: ${formatUSDC(strategy.orderAmount)}
      </div>
      <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>
        {t('whaleMonitorStrategy.list.priceRange')}: {strategy.minPrice} ~ {strategy.maxPrice}
        &nbsp;|&nbsp;
        {t('whaleMonitorStrategy.list.cooldown')}: {strategy.cooldownSeconds}{t('whaleMonitorStrategy.list.seconds')}
      </div>
      <Space>
        <Button size="small" icon={<EditOutlined />} onClick={() => showEditModal(strategy)}>
          {t('whaleMonitorStrategy.list.edit')}
        </Button>
        <Button size="small" icon={<UnorderedListOutlined />} onClick={() => showTriggerRecords(strategy.id)}>
          {t('whaleMonitorStrategy.list.viewTriggers')}
        </Button>
        <Popconfirm title={t('whaleMonitorStrategy.list.deleteConfirm')} onConfirm={() => handleDelete(strategy.id)}>
          <Button size="small" danger icon={<DeleteOutlined />}>
            {t('whaleMonitorStrategy.list.delete')}
          </Button>
        </Popconfirm>
      </Space>
    </Card>
  )

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
          <h2 style={{ margin: 0 }}>{t('whaleMonitorStrategy.list.title')}</h2>
          <Space wrap>
            <Select
              allowClear
              placeholder={t('whaleMonitorStrategy.list.account')}
              style={{ width: 160 }}
              value={filterAccountId}
              onChange={setFilterAccountId}
              options={accounts.map((a: Account) => ({
                label: a.accountName || `${a.walletAddress?.slice(0, 6)}...${a.walletAddress?.slice(-4)}`,
                value: a.id
              }))}
            />
            <Select
              allowClear
              placeholder={t('whaleMonitorStrategy.list.enabled')}
              style={{ width: 100 }}
              value={filterEnabled}
              onChange={setFilterEnabled}
              options={[
                { label: t('whaleMonitorStrategy.list.enable'), value: true },
                { label: t('whaleMonitorStrategy.list.disable'), value: false }
              ]}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={showCreateModal}>
              {t('whaleMonitorStrategy.list.addStrategy')}
            </Button>
          </Space>
        </div>

        {isMobile ? (
          <div>{strategies.map(renderMobileCard)}</div>
        ) : (
          <Table
            dataSource={strategies}
            columns={columns}
            rowKey="id"
            loading={loading}
            pagination={false}
            scroll={{ x: 1200 }}
            size="small"
          />
        )}
      </Card>

      <Modal
        title={editingStrategy ? t('whaleMonitorStrategy.list.edit') : t('whaleMonitorStrategy.list.addStrategy')}
        open={formVisible}
        onOk={handleSubmit}
        onCancel={() => setFormVisible(false)}
        confirmLoading={submitting}
        width={isMobile ? '95%' : 600}
        okText={editingStrategy ? t('whaleMonitorStrategy.form.update') : t('whaleMonitorStrategy.form.create')}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="accountId" label={t('whaleMonitorStrategy.form.selectAccount')} rules={[{ required: true }]}>
            <Select
              options={accounts.map((a: Account) => ({
                label: a.accountName || `${a.walletAddress?.slice(0, 6)}...${a.walletAddress?.slice(-4)}`,
                value: a.id
              }))}
            />
          </Form.Item>
          <Form.Item name="name" label={t('whaleMonitorStrategy.form.strategyName')}>
            <Input placeholder={t('whaleMonitorStrategy.form.strategyNamePlaceholder')} />
          </Form.Item>
          <Form.Item label={t('whaleMonitorStrategy.form.conditionIds')} required>
            <div>
              {selectedMarkets.length > 0 && (
                <div style={{ marginBottom: 8, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {selectedMarkets.map(m => (
                    <Tag
                      key={m.conditionId}
                      closable
                      onClose={() =>
                        setSelectedMarkets(prev => prev.filter(item => item.conditionId !== m.conditionId))
                      }
                    >
                      {m.title}
                    </Tag>
                  ))}
                </div>
              )}
              <Button
                block={isMobile}
                onClick={goToMarketSelect}
                style={{ minHeight: 44, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
              >
                <span>
                  {selectedMarkets.length > 0
                    ? t('whaleMonitorStrategy.form.marketsSelected', { count: selectedMarkets.length })
                    : t('whaleMonitorStrategy.form.selectMarkets')}
                </span>
                <RightOutlined />
              </Button>
            </div>
          </Form.Item>
          <Form.Item name="windowSeconds" label={t('whaleMonitorStrategy.form.windowSeconds')} rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="thresholdAmount" label={t('whaleMonitorStrategy.form.thresholdAmount')} rules={[{ required: true }]}>
            <InputNumber min={0.01} step={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="orderAmount" label={t('whaleMonitorStrategy.form.orderAmount')} rules={[{ required: true }]}>
            <InputNumber min={1} step={1} style={{ width: '100%' }} />
          </Form.Item>
          <Space>
            <Form.Item name="minPrice" label={t('whaleMonitorStrategy.form.minPrice')} rules={[{ required: true }]}>
              <InputNumber min={0} max={1} step={0.01} precision={2} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="maxPrice" label={t('whaleMonitorStrategy.form.maxPrice')} rules={[{ required: true }]}>
              <InputNumber min={0} max={1} step={0.01} precision={2} style={{ width: 120 }} />
            </Form.Item>
          </Space>
          <div style={{ fontSize: 12, color: '#999', marginTop: -16, marginBottom: 16 }}>
            {t('whaleMonitorStrategy.form.priceTip')}
          </div>
          <Form.Item name="cooldownSeconds" label={t('whaleMonitorStrategy.form.cooldownSeconds')} rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="enabled" label={t('whaleMonitorStrategy.form.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('whaleMonitorStrategy.triggerRecords.title')}
        open={triggerVisible}
        onCancel={() => setTriggerVisible(false)}
        footer={null}
        width={isMobile ? '95%' : 900}
      >
        <Tabs
          activeKey={triggerTab}
          onChange={handleTriggerTabChange}
          items={[
            { key: 'success', label: t('whaleMonitorStrategy.triggerRecords.successTab') },
            { key: 'fail', label: t('whaleMonitorStrategy.triggerRecords.failTab') }
          ]}
        />
        {triggerRecords.length === 0 ? (
          <Empty description={triggerTab === 'success' ? t('whaleMonitorStrategy.triggerRecords.emptySuccess') : t('whaleMonitorStrategy.triggerRecords.emptyFail')} />
        ) : (
          <Table
            dataSource={triggerRecords}
            columns={triggerColumns}
            rowKey="id"
            loading={triggerLoading}
            size="small"
            scroll={{ x: isMobile ? 700 : 'auto' }}
            pagination={{
              current: triggerPage,
              total: triggerTotal,
              pageSize: 20,
              onChange: (page) => {
                setTriggerPage(page)
                fetchTriggerRecords(triggerStrategyId, page, triggerTab === 'all' ? undefined : triggerTab)
              }
            }}
          />
        )}
      </Modal>
    </div>
  )
}

export default WhaleMonitorStrategyList
