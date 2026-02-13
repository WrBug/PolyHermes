import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Card,
  Table,
  Button,
  Space,
  Tag,
  Popconfirm,
  Switch,
  message,
  Select,
  Modal,
  Alert,
  Form,
  Input,
  InputNumber,
  Radio,
  Spin
} from 'antd'
import { PlusOutlined, EditOutlined, UnorderedListOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CryptoTailStrategyDto, CryptoTailStrategyTriggerDto, CryptoTailMarketOptionDto } from '../types'
import { formatUSDC, formatNumber } from '../utils'

const CryptoTailStrategyList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [list, setList] = useState<CryptoTailStrategyDto[]>([])
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<{ accountId?: number; enabled?: boolean }>({})
  const [systemConfig, setSystemConfig] = useState<{ builderApiKeyConfigured?: boolean; autoRedeemEnabled?: boolean } | null>(null)
  const [redeemModalOpen, setRedeemModalOpen] = useState(false)
  const [formModalOpen, setFormModalOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [marketOptions, setMarketOptions] = useState<CryptoTailMarketOptionDto[]>([])
  const [triggersModalOpen, setTriggersModalOpen] = useState(false)
  const [, setTriggersStrategyId] = useState<number | null>(null)
  const [triggers, setTriggers] = useState<CryptoTailStrategyTriggerDto[]>([])
  const [, setTriggersTotal] = useState(0)
  const [triggersLoading, setTriggersLoading] = useState(false)
  const [form] = Form.useForm()

  useEffect(() => {
    fetchAccounts()
    fetchSystemConfig()
    fetchMarketOptions()
  }, [])

  useEffect(() => {
    fetchList()
  }, [filters])

  const fetchSystemConfig = async () => {
    try {
      const res = await apiService.systemConfig.get()
      if (res.data.code === 0 && res.data.data) {
        setSystemConfig(res.data.data)
      }
    } catch {
      setSystemConfig(null)
    }
  }

  const fetchMarketOptions = async () => {
    try {
      const res = await apiService.cryptoTailStrategy.marketOptions()
      if (res.data.code === 0 && res.data.data) {
        setMarketOptions(res.data.data)
      }
    } catch {
      setMarketOptions([])
    }
  }

  const fetchList = async () => {
    setLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.list(filters)
      if (res.data.code === 0 && res.data.data) {
        setList(res.data.data.list ?? [])
      } else {
        message.error(res.data.msg || t('cryptoTailStrategy.list.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('cryptoTailStrategy.list.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const openAddModal = () => {
    const needApiKey = !systemConfig?.builderApiKeyConfigured
    const needAutoRedeem = !systemConfig?.autoRedeemEnabled
    if (needApiKey || needAutoRedeem) {
      setRedeemModalOpen(true)
      return
    }
    setEditingId(null)
    form.resetFields()
    form.setFieldsValue({
      enabled: true,
      amountMode: 'RATIO',
      maxPrice: '1',
      windowStartMinutes: 0,
      windowStartSeconds: 0
    })
    setFormModalOpen(true)
  }

  const openEditModal = (record: CryptoTailStrategyDto) => {
    setEditingId(record.id)
    form.setFieldsValue({
      accountId: record.accountId,
      name: record.name,
      marketSlugPrefix: record.marketSlugPrefix,
      windowStartMinutes: Math.floor(record.windowStartSeconds / 60),
      windowStartSeconds: record.windowStartSeconds % 60,
      windowEndMinutes: Math.floor(record.windowEndSeconds / 60),
      windowEndSeconds: record.windowEndSeconds % 60,
      minPrice: record.minPrice,
      maxPrice: record.maxPrice,
      amountMode: record.amountMode,
      amountValue: record.amountValue,
      enabled: record.enabled
    })
    setFormModalOpen(true)
  }

  const handleFormSubmit = async () => {
    try {
      const v = await form.validateFields()
      // 新建与编辑均按当前选择的市场 slug 取周期，编辑时无 Form.Item 的 intervalSeconds 不会在 v 中
      const interval = marketOptions.find((m) => m.slug === v.marketSlugPrefix)?.intervalSeconds ?? 300
      const windowStartSeconds = (v.windowStartMinutes ?? 0) * 60 + (v.windowStartSeconds ?? 0)
      const windowEndSeconds = (v.windowEndMinutes ?? 0) * 60 + (v.windowEndSeconds ?? 0)
      if (windowStartSeconds > windowEndSeconds) {
        message.error(t('cryptoTailStrategy.form.timeWindowStartLEEnd'))
        return
      }
      const maxWindow = interval
      if (windowEndSeconds > maxWindow) {
        message.error(t('cryptoTailStrategy.form.timeWindowExceed'))
        return
      }
      const payload = {
        accountId: v.accountId as number,
        name: v.name as string | undefined,
        marketSlugPrefix: v.marketSlugPrefix as string,
        intervalSeconds: interval,
        windowStartSeconds,
        windowEndSeconds,
        minPrice: String(v.minPrice ?? 0),
        maxPrice: v.maxPrice != null ? String(v.maxPrice) : undefined,
        amountMode: v.amountMode as string,
        amountValue: String(v.amountValue ?? 0),
        enabled: v.enabled !== false
      }
      if (editingId) {
        const res = await apiService.cryptoTailStrategy.update({
          strategyId: editingId,
          name: payload.name,
          windowStartSeconds: payload.windowStartSeconds,
          windowEndSeconds: payload.windowEndSeconds,
          minPrice: payload.minPrice,
          maxPrice: payload.maxPrice,
          amountMode: payload.amountMode,
          amountValue: payload.amountValue,
          enabled: payload.enabled
        })
        if (res.data.code === 0) {
          message.success(t('common.success'))
          setFormModalOpen(false)
          fetchList()
        } else {
          message.error(res.data.msg || t('common.failed'))
        }
      } else {
        const res = await apiService.cryptoTailStrategy.create(payload)
        if (res.data.code === 0) {
          message.success(t('common.success'))
          setFormModalOpen(false)
          fetchList()
        } else {
          message.error(res.data.msg || t('common.failed'))
        }
      }
    } catch (e) {
      if ((e as { errorFields?: unknown[] })?.errorFields) {
        return
      }
      message.error((e as Error).message)
    }
  }

  const handleToggle = async (record: CryptoTailStrategyDto) => {
    try {
      const res = await apiService.cryptoTailStrategy.update({
        strategyId: record.id,
        enabled: !record.enabled
      })
      if (res.data.code === 0) {
        message.success(record.enabled ? t('cryptoTailStrategy.list.disable') : t('cryptoTailStrategy.list.enable'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const handleDelete = async (strategyId: number) => {
    try {
      const res = await apiService.cryptoTailStrategy.delete({ strategyId })
      if (res.data.code === 0) {
        message.success(t('common.success'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const openTriggers = async (strategyId: number) => {
    setTriggersStrategyId(strategyId)
    setTriggersModalOpen(true)
    setTriggersLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.triggers({ strategyId, page: 1, pageSize: 50 })
      if (res.data.code === 0 && res.data.data) {
        setTriggers(res.data.data.list ?? [])
        setTriggersTotal(res.data.data.total ?? 0)
      }
    } finally {
      setTriggersLoading(false)
    }
  }

  const formatTimeWindow = (startSec: number, endSec: number): string => {
    const sm = Math.floor(startSec / 60)
    const ss = startSec % 60
    const em = Math.floor(endSec / 60)
    const es = endSec % 60
    return `${sm} ${t('cryptoTailStrategy.form.minute')} ${ss} ${t('cryptoTailStrategy.form.second')} ~ ${em} ${t('cryptoTailStrategy.form.minute')} ${es} ${t('cryptoTailStrategy.form.second')}`
  }

  const formatLastTrigger = (ts?: number) => {
    if (ts == null) return '-'
    const d = new Date(ts)
    return d.toLocaleString()
  }

  const formatPriceRange = (minPrice: string, maxPrice: string): string => {
    const min = formatNumber(minPrice, 2)
    const max = formatNumber(maxPrice, 2)
    if (min === '' || max === '') return '-'
    return `${min} ~ ${max}`
  }

  const pnlColor = (value: string | number | null | undefined): string | undefined => {
    if (value == null || value === '') return undefined
    const num = typeof value === 'string' ? Number(value) : value
    if (Number.isNaN(num)) return undefined
    if (num > 0) return '#52c41a'
    if (num < 0) return '#ff4d4f'
    return undefined
  }

  const columns = [
    {
      title: t('cryptoTailStrategy.list.strategyName'),
      dataIndex: 'name',
      key: 'name',
      width: isMobile ? 100 : 140,
      render: (name: string | undefined, r: CryptoTailStrategyDto) => name || (r.marketTitle ?? r.marketSlugPrefix) || '-'
    },
    {
      title: t('cryptoTailStrategy.list.market'),
      key: 'market',
      width: isMobile ? 120 : 200,
      render: (_: unknown, r: CryptoTailStrategyDto) =>
        marketOptions.find((m) => m.slug === r.marketSlugPrefix)?.title ?? r.marketTitle ?? r.marketSlugPrefix ?? '-'
    },
    {
      title: t('cryptoTailStrategy.list.timeWindow'),
      key: 'timeWindow',
      width: isMobile ? 140 : 180,
      render: (_: unknown, r: CryptoTailStrategyDto) => formatTimeWindow(r.windowStartSeconds, r.windowEndSeconds)
    },
    {
      title: t('cryptoTailStrategy.list.priceRange'),
      key: 'priceRange',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => formatPriceRange(r.minPrice, r.maxPrice)
    },
    {
      title: t('cryptoTailStrategy.list.amountMode'),
      key: 'amountMode',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) =>
        (r.amountMode?.toUpperCase() ?? '') === 'RATIO'
          ? `${t('cryptoTailStrategy.list.ratio')} ${formatNumber(r.amountValue, 2) || '0'}%`
          : `${t('cryptoTailStrategy.list.fixed')} ${formatUSDC(r.amountValue)} USDC`
    },
    {
      title: t('common.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled: boolean, record: CryptoTailStrategyDto) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggle(record)}
          checkedChildren={t('cryptoTailStrategy.list.enable')}
          unCheckedChildren={t('cryptoTailStrategy.list.disable')}
        />
      )
    },
    {
      title: t('cryptoTailStrategy.list.recentTrigger'),
      dataIndex: 'lastTriggerAt',
      key: 'lastTriggerAt',
      width: isMobile ? 100 : 160,
      render: (ts: number | undefined) => formatLastTrigger(ts)
    },
    {
      title: t('cryptoTailStrategy.list.totalRealizedPnl'),
      key: 'totalRealizedPnl',
      width: isMobile ? 90 : 110,
      render: (_: unknown, r: CryptoTailStrategyDto) => {
        const text = r.totalRealizedPnl != null ? `${formatUSDC(r.totalRealizedPnl)} USDC` : '-'
        const color = pnlColor(r.totalRealizedPnl)
        return color ? <span style={{ color }}>{text}</span> : text
      }
    },
    {
      title: t('cryptoTailStrategy.list.winRate'),
      key: 'winRate',
      width: isMobile ? 70 : 80,
      render: (_: unknown, r: CryptoTailStrategyDto) =>
        r.winRate != null ? `${(Number(r.winRate) * 100).toFixed(1)}%` : '-'
    },
    {
      title: t('cryptoTailStrategy.list.actions'),
      key: 'actions',
      width: isMobile ? 120 : 200,
      fixed: 'right' as const,
      render: (_: unknown, record: CryptoTailStrategyDto) => (
        <Space size="small" wrap>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditModal(record)}>
            {t('cryptoTailStrategy.list.edit')}
          </Button>
          <Button
            type="link"
            size="small"
            icon={<UnorderedListOutlined />}
            onClick={() => openTriggers(record.id)}
          >
            {t('cryptoTailStrategy.list.viewTriggers')}
          </Button>
          <Popconfirm
            title={t('cryptoTailStrategy.list.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button type="link" size="small" danger>
              {t('cryptoTailStrategy.list.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  const selectedMarket = Form.useWatch('marketSlugPrefix', form)
  const intervalSeconds = marketOptions.find((m) => m.slug === selectedMarket)?.intervalSeconds ?? 300
  const maxMinutes = Math.floor(intervalSeconds / 60)

  // 新建时：选择市场后，区间开始默认 0分0秒，区间结束默认 x分0秒（x=周期）
  useEffect(() => {
    if (!formModalOpen || editingId != null || !selectedMarket) return
    const intervalMin = Math.floor(intervalSeconds / 60)
    form.setFieldsValue({
      windowStartMinutes: 0,
      windowStartSeconds: 0,
      windowEndMinutes: intervalMin,
      windowEndSeconds: 0
    })
  }, [formModalOpen, editingId, selectedMarket, intervalSeconds])

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <h1 style={{ marginBottom: 16, fontSize: isMobile ? 20 : 24 }}>{t('cryptoTailStrategy.list.title')}</h1>
      <Alert
        type="warning"
        showIcon
        message={t('cryptoTailStrategy.list.walletTip')}
        style={{ marginBottom: 16 }}
      />
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={openAddModal}>
            {t('cryptoTailStrategy.list.addStrategy')}
          </Button>
          <Select
            placeholder={t('cryptoTailStrategy.form.selectAccount')}
            allowClear
            style={{ minWidth: 160 }}
            onChange={(id) => setFilters((f) => ({ ...f, accountId: id ?? undefined }))}
            value={filters.accountId}
            options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
          />
          <Select
            placeholder={t('common.status')}
            allowClear
            style={{ width: 100 }}
            onChange={(en) => setFilters((f) => ({ ...f, enabled: en }))}
            value={filters.enabled}
            options={[
              { label: t('common.enabled'), value: true },
              { label: t('common.disabled'), value: false }
            ]}
          />
        </div>
        <Spin spinning={loading}>
          {isMobile ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {list.map((item) => (
                <Card key={item.id} size="small">
                  <div style={{ marginBottom: 8 }}>
                    <strong>{item.name || item.marketSlugPrefix || '-'}</strong>
                  </div>
                  <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>
                    {t('cryptoTailStrategy.list.timeWindow')}: {formatTimeWindow(item.windowStartSeconds, item.windowEndSeconds)}
                  </div>
                  <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>
                    {t('cryptoTailStrategy.list.priceRange')}: {formatPriceRange(item.minPrice, item.maxPrice)}
                  </div>
                  <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>
                    {t('cryptoTailStrategy.list.amountMode')}:{' '}
                    {(item.amountMode?.toUpperCase() ?? '') === 'RATIO'
                      ? `${t('cryptoTailStrategy.list.ratio')} ${formatNumber(item.amountValue, 2) || '0'}%`
                      : `${t('cryptoTailStrategy.list.fixed')} ${formatUSDC(item.amountValue)} USDC`}
                  </div>
                  <div style={{ fontSize: 12, color: '#666', marginBottom: 8 }}>
                    {t('cryptoTailStrategy.list.totalRealizedPnl')}:{' '}
                    {item.totalRealizedPnl != null ? (
                      <span style={{ color: pnlColor(item.totalRealizedPnl) ?? '#666' }}>
                        {formatUSDC(item.totalRealizedPnl)} USDC
                      </span>
                    ) : (
                      '-'
                    )}
                    {item.winRate != null ? ` · ${t('cryptoTailStrategy.list.winRate')}: ${(Number(item.winRate) * 100).toFixed(1)}%` : ''}
                  </div>
                  <Space>
                    <Switch
                      checked={item.enabled}
                      onChange={() => handleToggle(item)}
                      size="small"
                    />
                    <Button type="link" size="small" onClick={() => openEditModal(item)}>
                      {t('cryptoTailStrategy.list.edit')}
                    </Button>
                    <Button type="link" size="small" onClick={() => openTriggers(item.id)}>
                      {t('cryptoTailStrategy.list.viewTriggers')}
                    </Button>
                    <Popconfirm
                      title={t('cryptoTailStrategy.list.deleteConfirm')}
                      onConfirm={() => handleDelete(item.id)}
                      okText={t('common.confirm')}
                      cancelText={t('common.cancel')}
                    >
                      <Button type="link" size="small" danger>
                        {t('cryptoTailStrategy.list.delete')}
                      </Button>
                    </Popconfirm>
                  </Space>
                </Card>
              ))}
            </div>
          ) : (
            <Table
              rowKey="id"
              columns={columns}
              dataSource={list}
              pagination={{ pageSize: 20 }}
              scroll={{ x: 900 }}
            />
          )}
        </Spin>
      </Card>

      <Modal
        title={t('cryptoTailStrategy.redeemRequiredModal.title')}
        open={redeemModalOpen}
        onCancel={() => setRedeemModalOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setRedeemModalOpen(false)}>
            {t('cryptoTailStrategy.redeemRequiredModal.cancel')}
          </Button>,
          <Button
            key="go"
            type="primary"
            onClick={() => {
              setRedeemModalOpen(false)
              navigate('/system-settings')
            }}
          >
            {t('cryptoTailStrategy.redeemRequiredModal.goToSettings')}
          </Button>
        ]}
      >
        <p>{t('cryptoTailStrategy.redeemRequiredModal.description')}</p>
      </Modal>

      <Modal
        title={editingId ? t('cryptoTailStrategy.form.update') : t('cryptoTailStrategy.form.create')}
        open={formModalOpen}
        onCancel={() => setFormModalOpen(false)}
        onOk={handleFormSubmit}
        width={isMobile ? '100%' : 520}
        destroyOnClose
      >
        <Alert type="warning" showIcon message={t('cryptoTailStrategy.form.walletTip')} style={{ marginBottom: 16 }} />
        <Form form={form} layout="vertical" initialValues={{ amountMode: 'RATIO', maxPrice: '1', enabled: true }}>
          <Form.Item name="accountId" label={t('cryptoTailStrategy.form.selectAccount')} rules={[{ required: true }]}>
            <Select
              placeholder={t('cryptoTailStrategy.form.selectAccount')}
              options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
            />
          </Form.Item>
          <Form.Item name="name" label={t('cryptoTailStrategy.form.strategyName')}>
            <Input placeholder={t('cryptoTailStrategy.form.strategyNamePlaceholder')} />
          </Form.Item>
          <Form.Item name="marketSlugPrefix" label={t('cryptoTailStrategy.form.selectMarket')} rules={[{ required: true }]}>
            <Select
              placeholder={t('cryptoTailStrategy.form.selectMarket')}
              options={marketOptions.map((m) => ({ label: m.title, value: m.slug }))}
              disabled={!!editingId}
            />
          </Form.Item>
          {selectedMarket && (
            <>
              <Form.Item
                label={t('cryptoTailStrategy.form.timeWindowStart')}
                required
                style={{ marginBottom: 8 }}
              >
                <Space>
                  <Form.Item name="windowStartMinutes" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: maxMinutes + 1 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.minute')}</span>
                  <Form.Item name="windowStartSeconds" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: 60 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.second')}</span>
                </Space>
              </Form.Item>
              <Form.Item
                label={t('cryptoTailStrategy.form.timeWindowEnd')}
                required
              >
                <Space>
                  <Form.Item name="windowEndMinutes" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: maxMinutes + 1 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.minute')}</span>
                  <Form.Item name="windowEndSeconds" noStyle rules={[{ required: true }]}>
                    <Select
                      style={{ width: 70 }}
                      options={Array.from({ length: 60 }, (_, i) => ({ label: `${i}`, value: i }))}
                    />
                  </Form.Item>
                  <span>{t('cryptoTailStrategy.form.second')}</span>
                </Space>
              </Form.Item>
            </>
          )}
          <Form.Item name="minPrice" label={t('cryptoTailStrategy.form.minPrice')} rules={[{ required: true }]}>
            <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} stringMode />
          </Form.Item>
          <Form.Item name="maxPrice" label={t('cryptoTailStrategy.form.maxPrice')}>
            <InputNumber min={0} max={1} step={0.01} placeholder={t('cryptoTailStrategy.form.maxPricePlaceholder')} style={{ width: '100%' }} stringMode />
          </Form.Item>
          <Form.Item name="amountMode" label={t('cryptoTailStrategy.form.amountMode')} rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="RATIO">{t('cryptoTailStrategy.list.ratio')}</Radio>
              <Radio value="FIXED">{t('cryptoTailStrategy.list.fixed')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, curr) => prev.amountMode !== curr.amountMode}
          >
            {({ getFieldValue }) =>
              getFieldValue('amountMode') === 'RATIO' ? (
                <Form.Item name="amountValue" label={t('cryptoTailStrategy.form.ratioPercent')} rules={[{ required: true }]}>
                  <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} addonAfter="%" stringMode />
                </Form.Item>
              ) : (
                <Form.Item name="amountValue" label={t('cryptoTailStrategy.form.fixedUsdc')} rules={[{ required: true }]}>
                  <InputNumber min={1} style={{ width: '100%' }} addonAfter="USDC" stringMode />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="enabled" valuePropName="checked">
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('cryptoTailStrategy.triggerRecords.title')}
        open={triggersModalOpen}
        onCancel={() => setTriggersModalOpen(false)}
        footer={null}
        width={Math.min(800, window.innerWidth - 48)}
      >
        <Spin spinning={triggersLoading}>
          <Table
            rowKey="id"
            size="small"
            dataSource={triggers}
            columns={[
              {
                title: t('cryptoTailStrategy.triggerRecords.triggerTime'),
                dataIndex: 'createdAt',
                key: 'createdAt',
                render: (ts: number) => new Date(ts).toLocaleString()
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.direction'),
                dataIndex: 'outcomeIndex',
                key: 'outcomeIndex',
                render: (i: number) => (i === 0 ? t('cryptoTailStrategy.triggerRecords.up') : t('cryptoTailStrategy.triggerRecords.down'))
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.triggerPrice'),
                dataIndex: 'triggerPrice',
                key: 'triggerPrice',
                render: (v: string) => (formatNumber(v, 2) || '-')
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.amount'),
                dataIndex: 'amountUsdc',
                key: 'amountUsdc',
                render: (v: string) => `${formatUSDC(v)} USDC`
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.orderId'),
                dataIndex: 'orderId',
                key: 'orderId',
                ellipsis: true
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.status'),
                dataIndex: 'status',
                key: 'status',
                render: (s: string) => (
                  <Tag color={s === 'success' ? 'green' : 'red'}>
                    {s === 'success' ? t('cryptoTailStrategy.triggerRecords.success') : t('cryptoTailStrategy.triggerRecords.fail')}
                  </Tag>
                )
              },
              {
                title: t('cryptoTailStrategy.triggerRecords.realizedPnl'),
                dataIndex: 'realizedPnl',
                key: 'realizedPnl',
                render: (v: string | undefined, r: CryptoTailStrategyTriggerDto) => {
                  if (v == null || v === '') return r.resolved ? formatUSDC('0') : '-'
                  const num = Number(v)
                  const formatted = formatUSDC(String(Math.abs(num)))
                  const text = num >= 0 ? `+${formatted}` : `-${formatted}`
                  const color = pnlColor(v)
                  return color ? <span style={{ color }}>{text}</span> : text
                }
              }
            ]}
            pagination={false}
            scroll={{ x: 600 }}
          />
        </Spin>
      </Modal>
    </div>
  )
}

export default CryptoTailStrategyList
