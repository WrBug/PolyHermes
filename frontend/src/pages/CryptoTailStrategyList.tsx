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
  Spin,
  Tooltip,
  Tabs,
  DatePicker,
  Empty,
  Typography,
  Divider
} from 'antd'
import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'
import { PlusOutlined, EditOutlined, UnorderedListOutlined, InfoCircleOutlined, WarningOutlined, CalendarOutlined, ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
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
  const [triggersStrategyId, setTriggersStrategyId] = useState<number | null>(null)
  const [triggerTab, setTriggerTab] = useState<'success' | 'fail'>('success')
  const [triggerDateRange, setTriggerDateRange] = useState<[Dayjs | null, Dayjs | null]>([null, null])
  const [triggerPage, setTriggerPage] = useState(1)
  const [triggerPageSize, setTriggerPageSize] = useState(20)
  const [triggers, setTriggers] = useState<CryptoTailStrategyTriggerDto[]>([])
  const [triggersTotal, setTriggersTotal] = useState(0)
  const [triggersLoading, setTriggersLoading] = useState(false)
  const [form] = Form.useForm()

  /** 币安 API 健康状态：仅保留「不可用」的项，用于强提醒 */
  const [binanceUnhealthy, setBinanceUnhealthy] = useState<Array<{ name: string; message: string }>>([])
  const [binanceCheckLoading, setBinanceCheckLoading] = useState(false)

  const BINANCE_API_NAMES = ['币安 API', '币安 WebSocket']

  const fetchBinanceApiStatus = async () => {
    setBinanceCheckLoading(true)
    try {
      const res = await apiService.proxyConfig.checkApiHealth()
      if (res.data.code === 0 && res.data.data?.apis) {
        const unhealthy = res.data.data.apis.filter(
          (api) => BINANCE_API_NAMES.includes(api.name) && api.status !== 'success'
        )
        setBinanceUnhealthy(unhealthy.map((api) => ({ name: api.name, message: api.message })))
      } else {
        setBinanceUnhealthy([])
      }
    } catch {
      setBinanceUnhealthy([{ name: '币安 API', message: '' }, { name: '币安 WebSocket', message: '' }])
    } finally {
      setBinanceCheckLoading(false)
    }
  }

  useEffect(() => {
    fetchAccounts()
    fetchSystemConfig()
    fetchMarketOptions()
  }, [])

  useEffect(() => {
    fetchBinanceApiStatus()
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
      minSpreadMode: 'AUTO',
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
      minSpreadMode: record.minSpreadMode ?? 'AUTO',
      minSpreadValue: record.minSpreadValue ?? undefined,
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
        minSpreadMode: (v.minSpreadMode as string) || 'AUTO',
        minSpreadValue: v.minSpreadMode === 'FIXED' && v.minSpreadValue != null ? String(v.minSpreadValue) : (v.minSpreadMode === 'AUTO' && v.minSpreadValue != null ? String(v.minSpreadValue) : undefined),
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
          minSpreadMode: payload.minSpreadMode,
          minSpreadValue: payload.minSpreadValue,
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
        const res = await apiService.cryptoTailStrategy.create({
          ...payload,
          minSpreadValue: payload.minSpreadMode === 'FIXED' ? payload.minSpreadValue : undefined
        })
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

  type TriggerLoadOpts = { page?: number; pageSize?: number; dateRange?: [Dayjs | null, Dayjs | null] }

  const loadTriggerRecords = async (
    strategyId: number,
    status: 'success' | 'fail',
    opts?: TriggerLoadOpts
  ) => {
    const page = opts?.page ?? triggerPage
    const pageSize = opts?.pageSize ?? triggerPageSize
    const range = opts?.dateRange ?? triggerDateRange
    const startDate =
      range[0] != null ? range[0].startOf('day').valueOf() : undefined
    const endDate =
      range[1] != null ? range[1].endOf('day').valueOf() : undefined
    setTriggersLoading(true)
    try {
      const res = await apiService.cryptoTailStrategy.triggers({
        strategyId,
        page,
        pageSize,
        status,
        startDate,
        endDate
      })
      if (res.data.code === 0 && res.data.data) {
        setTriggers(res.data.data.list ?? [])
        setTriggersTotal(res.data.data.total ?? 0)
      }
    } finally {
      setTriggersLoading(false)
    }
  }

  const openTriggers = async (strategyId: number) => {
    setTriggersStrategyId(strategyId)
    setTriggerTab('success')
    setTriggerDateRange([null, null])
    setTriggerPage(1)
    setTriggerPageSize(20)
    setTriggersModalOpen(true)
    await loadTriggerRecords(strategyId, 'success', { page: 1, pageSize: 20, dateRange: [null, null] })
  }

  const onTriggerTabChange = (key: string) => {
    const next = key === 'success' ? 'success' : 'fail'
    setTriggerTab(next)
    setTriggers([])
    setTriggerPage(1)
    if (triggersStrategyId != null) {
      loadTriggerRecords(triggersStrategyId, next, { page: 1 })
    }
  }

  const onTriggerDateRangeChange = (dates: [Dayjs | null, Dayjs | null] | null) => {
    const next = dates ?? [null, null]
    setTriggerDateRange(next)
    setTriggerPage(1)
    if (triggersStrategyId != null) {
      loadTriggerRecords(triggersStrategyId, triggerTab, { page: 1, dateRange: next })
    }
  }

  const onTriggerPageChange = (page: number, pageSize: number) => {
    setTriggerPage(page)
    setTriggerPageSize(pageSize)
    if (triggersStrategyId != null) {
      loadTriggerRecords(triggersStrategyId, triggerTab, { page, pageSize })
    }
  }

  const disabledTriggerEndDate = (current: Dayjs) => current && current.isAfter(dayjs(), 'day')

  const formatTimeWindow = (startSec: number, endSec: number, wrap = true): string => {
    const sm = Math.floor(startSec / 60)
    const ss = startSec % 60
    const em = Math.floor(endSec / 60)
    const es = endSec % 60
    const sep = wrap ? '\n~ ' : ' ~ '
    return `${sm} ${t('cryptoTailStrategy.form.minute')} ${ss} ${t('cryptoTailStrategy.form.second')}${sep}${em} ${t('cryptoTailStrategy.form.minute')} ${es} ${t('cryptoTailStrategy.form.second')}`
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

  const getAccountLabel = (accountId: number) => accounts.find((a) => a.id === accountId)?.accountName || `#${accountId}`

  const columns = [
    {
      title: t('common.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean, record: CryptoTailStrategyDto) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggle(record)}
          size="small"
        />
      )
    },
    {
      title: t('cryptoTailStrategy.list.strategyName'),
      dataIndex: 'name',
      key: 'name',
      width: isMobile ? 100 : 160,
      render: (name: string | undefined, r: CryptoTailStrategyDto) => (
        <Typography.Text strong style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {name || (r.marketTitle ?? r.marketSlugPrefix) || '-'}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.account'),
      dataIndex: 'accountId',
      key: 'accountId',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text type="secondary" style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {getAccountLabel(r.accountId)}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.market'),
      key: 'market',
      width: isMobile ? 120 : 200,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {marketOptions.find((m) => m.slug === r.marketSlugPrefix)?.title ?? r.marketTitle ?? r.marketSlugPrefix ?? '-'}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.timeWindow'),
      key: 'timeWindow',
      width: isMobile ? 100 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text type="secondary" style={{ wordBreak: 'break-word', whiteSpace: 'pre-line' }}>
          {formatTimeWindow(r.windowStartSeconds, r.windowEndSeconds)}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.priceRange'),
      key: 'priceRange',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text type="secondary" style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {formatPriceRange(r.minPrice, r.maxPrice)}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.amountMode'),
      key: 'amountMode',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => (
        <Typography.Text type="secondary" style={{ wordBreak: 'break-word', whiteSpace: 'normal' }}>
          {(r.amountMode?.toUpperCase() ?? '') === 'RATIO'
            ? `${t('cryptoTailStrategy.list.ratio')} ${formatNumber(r.amountValue, 2) || '0'}%`
            : `${t('cryptoTailStrategy.list.fixed')} ${formatUSDC(r.amountValue)} USDC`}
        </Typography.Text>
      )
    },
    {
      title: t('cryptoTailStrategy.list.totalRealizedPnl'),
      key: 'totalRealizedPnl',
      width: isMobile ? 90 : 120,
      render: (_: unknown, r: CryptoTailStrategyDto) => {
        const text = r.totalRealizedPnl != null ? `${formatUSDC(r.totalRealizedPnl)} USDC` : '-'
        const color = pnlColor(r.totalRealizedPnl)
        return color ? (
          <Typography.Text style={{ color, fontWeight: 500 }}>{text}</Typography.Text>
        ) : (
          <Typography.Text type="secondary">{text}</Typography.Text>
        )
      }
    },
    {
      title: t('cryptoTailStrategy.list.winRate'),
      key: 'winRate',
      width: isMobile ? 70 : 90,
      render: (_: unknown, r: CryptoTailStrategyDto) =>
        r.winRate != null ? (
          <Tag color="blue">{(Number(r.winRate) * 100).toFixed(1)}%</Tag>
        ) : (
          <Typography.Text type="secondary">-</Typography.Text>
        )
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
      {binanceUnhealthy.length > 0 && (
        <Alert
          type="error"
          showIcon
          icon={<WarningOutlined />}
          message={t('cryptoTailStrategy.binanceApiAlert.title')}
          description={
            <div>
              <p style={{ marginBottom: 8 }}>{t('cryptoTailStrategy.binanceApiAlert.description')}</p>
              <ul style={{ marginBottom: 8, paddingLeft: 20 }}>
                {binanceUnhealthy.map((item, i) => (
                  <li key={i}>
                    <strong>{item.name}</strong>
                    {item.message ? `: ${item.message}` : ''}
                  </li>
                ))}
              </ul>
              <Button
                type="primary"
                danger
                size="small"
                loading={binanceCheckLoading}
                onClick={fetchBinanceApiStatus}
              >
                {t('cryptoTailStrategy.binanceApiAlert.recheck')}
              </Button>
            </div>
          }
          style={{ marginBottom: 16 }}
        />
      )}
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
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              {list.map((item) => (
                <Card
                  key={item.id}
                  size="small"
                  styles={{ body: { padding: 16 } }}
                  style={{ borderLeft: `3px solid ${item.enabled ? 'var(--ant-colorSuccess)' : 'var(--ant-colorBorder)'}` }}
                >
                  <Typography.Text strong style={{ fontSize: 15, wordBreak: 'break-word', whiteSpace: 'normal', display: 'block', marginBottom: 8 }}>
                    {item.name || (item.marketTitle ?? item.marketSlugPrefix) || '-'}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4, fontSize: 13, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                    {t('cryptoTailStrategy.list.account')}: {getAccountLabel(item.accountId)}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8, fontSize: 13, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                    {marketOptions.find((m) => m.slug === item.marketSlugPrefix)?.title ?? item.marketSlugPrefix ?? '-'}
                  </Typography.Text>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 16px', marginBottom: 12 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                      {t('cryptoTailStrategy.list.timeWindow')}
                    </Typography.Text>
                    <Typography.Text style={{ fontSize: 12 }}>{formatTimeWindow(item.windowStartSeconds, item.windowEndSeconds, false)}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                      {t('cryptoTailStrategy.list.priceRange')}
                    </Typography.Text>
                    <Typography.Text style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'normal' }}>{formatPriceRange(item.minPrice, item.maxPrice)}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                      {t('cryptoTailStrategy.list.amountMode')}
                    </Typography.Text>
                    <Typography.Text style={{ fontSize: 12, wordBreak: 'break-word', whiteSpace: 'normal' }}>
                      {(item.amountMode?.toUpperCase() ?? '') === 'RATIO'
                        ? `${t('cryptoTailStrategy.list.ratio')} ${formatNumber(item.amountValue, 2) || '0'}%`
                        : `${t('cryptoTailStrategy.list.fixed')} ${formatUSDC(item.amountValue)} USDC`}
                    </Typography.Text>
                  </div>
                  <Divider style={{ margin: '10px 0' }} />
                  <Space wrap style={{ marginBottom: 12 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {t('cryptoTailStrategy.list.totalRealizedPnl')}:{' '}
                    </Typography.Text>
                    {item.totalRealizedPnl != null ? (
                      <Typography.Text style={{ color: pnlColor(item.totalRealizedPnl) ?? undefined, fontWeight: 500, fontSize: 12 }}>
                        {formatUSDC(item.totalRealizedPnl)} USDC
                      </Typography.Text>
                    ) : (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>-</Typography.Text>
                    )}
                    {item.winRate != null && (
                      <>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>·</Typography.Text>
                        <Tag color="blue" style={{ margin: 0 }}>{t('cryptoTailStrategy.list.winRate')} {(Number(item.winRate) * 100).toFixed(1)}%</Tag>
                      </>
                    )}
                  </Space>
                  <Divider style={{ margin: '10px 0' }} />
                  <Space wrap size="small">
                    <Switch
                      checked={item.enabled}
                      onChange={() => handleToggle(item)}
                      size="small"
                    />
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditModal(item)}>
                      {t('cryptoTailStrategy.list.edit')}
                    </Button>
                    <Button type="link" size="small" icon={<UnorderedListOutlined />} onClick={() => openTriggers(item.id)}>
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
        <Form form={form} layout="vertical" initialValues={{ amountMode: 'RATIO', maxPrice: '1', minSpreadMode: 'AUTO', enabled: true }}>
          <Form.Item name="accountId" label={t('cryptoTailStrategy.form.selectAccount')} rules={[{ required: true }]}>
            <Select
              placeholder={t('cryptoTailStrategy.form.selectAccount')}
              options={accounts.map((a) => ({ label: a.accountName || `#${a.id}`, value: a.id }))}
              disabled={!!editingId}
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
          <Form.Item
            name="minSpreadMode"
            label={
              <Space size={4}>
                <span>{t('cryptoTailStrategy.form.minSpreadMode')}</span>
                <Tooltip title={t('cryptoTailStrategy.form.minSpreadModeTip')}>
                  <InfoCircleOutlined style={{ color: '#999', cursor: 'help', fontSize: 14 }} />
                </Tooltip>
              </Space>
            }
          >
            <Radio.Group>
              <Radio value="AUTO">{t('cryptoTailStrategy.form.minSpreadModeAuto')}</Radio>
              <Radio value="FIXED">{t('cryptoTailStrategy.form.minSpreadModeFixed')}</Radio>
              <Radio value="NONE">{t('cryptoTailStrategy.form.minSpreadModeNone')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, curr) => prev.minSpreadMode !== curr.minSpreadMode}
          >
            {({ getFieldValue }) =>
              getFieldValue('minSpreadMode') === 'FIXED' ? (
                <Form.Item
                  name="minSpreadValue"
                  label={t('cryptoTailStrategy.form.minSpreadValue')}
                  rules={[{ required: true }]}
                >
                  <InputNumber
                    min={0}
                    step={1}
                    placeholder={t('cryptoTailStrategy.form.minSpreadValuePlaceholder')}
                    style={{ width: '100%' }}
                    stringMode
                  />
                </Form.Item>
              ) : null
            }
          </Form.Item>
          <Form.Item name="enabled" valuePropName="checked">
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={
          <Space>
            <UnorderedListOutlined style={{ fontSize: 18, color: 'var(--ant-colorPrimary)' }} />
            <span>{t('cryptoTailStrategy.triggerRecords.title')}</span>
          </Space>
        }
        open={triggersModalOpen}
        onCancel={() => setTriggersModalOpen(false)}
        footer={null}
        width={Math.min(900, window.innerWidth - 48)}
        styles={{ body: { paddingTop: 16 } }}
      >
        <Card size="small" style={{ marginBottom: 16, background: 'var(--ant-colorFillQuaternary)' }}>
          <Space wrap align="center" size="middle">
            <Space align="center">
              <CalendarOutlined style={{ color: 'var(--ant-colorTextSecondary)' }} />
              <Typography.Text type="secondary">{t('cryptoTailStrategy.triggerRecords.timeRange')}</Typography.Text>
            </Space>
            <DatePicker.RangePicker
              value={triggerDateRange}
              onChange={onTriggerDateRangeChange}
              format="YYYY-MM-DD"
              placeholder={[t('cryptoTailStrategy.triggerRecords.startDate'), t('cryptoTailStrategy.triggerRecords.endDate')]}
              allowClear
              disabledDate={disabledTriggerEndDate}
              style={{ minWidth: 240 }}
            />
          </Space>
        </Card>
        <Tabs
          activeKey={triggerTab}
          onChange={onTriggerTabChange}
          items={[
            {
              key: 'success',
              label: t('cryptoTailStrategy.triggerRecords.successTab'),
              children: (
                <Spin spinning={triggersLoading}>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={triggerTab === 'success' ? triggers : []}
                    locale={{
                      emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.triggerRecords.emptySuccess')} />
                    }}
                    columns={[
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerTime'),
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 172,
                        render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.direction'),
                        dataIndex: 'outcomeIndex',
                        key: 'outcomeIndex',
                        width: 80,
                        align: 'center',
                        render: (i: number) =>
                          i === 0 ? (
                            <Tag icon={<ArrowUpOutlined />} color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
                          ) : (
                            <Tag icon={<ArrowDownOutlined />} color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>
                          )
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerPrice'),
                        dataIndex: 'triggerPrice',
                        key: 'triggerPrice',
                        width: 100,
                        render: (v: string) => (formatNumber(v, 2) || '-')
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.amount'),
                        dataIndex: 'amountUsdc',
                        key: 'amountUsdc',
                        width: 110,
                        render: (v: string) => `${formatUSDC(v)} USDC`
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.realizedPnl'),
                        dataIndex: 'realizedPnl',
                        key: 'realizedPnl',
                        width: 100,
                        render: (v: string | undefined, r: CryptoTailStrategyTriggerDto) => {
                          if (v == null || v === '') return r.resolved ? formatUSDC('0') : '-'
                          const num = Number(v)
                          const formatted = formatUSDC(String(Math.abs(num)))
                          const text = num >= 0 ? `+${formatted}` : `-${formatted}`
                          const color = pnlColor(v)
                          return color ? <Typography.Text style={{ color, fontWeight: 500 }}>{text}</Typography.Text> : text
                        }
                      }
                    ]}
                    pagination={{
                      current: triggerPage,
                      pageSize: triggerPageSize,
                      total: triggersTotal,
                      showSizeChanger: true,
                      showTotal: (total) => t('cryptoTailStrategy.triggerRecords.totalCount').replace('{count}', String(total)),
                      onChange: onTriggerPageChange
                    }}
                    scroll={{ x: 540 }}
                  />
                </Spin>
              )
            },
            {
              key: 'fail',
              label: t('cryptoTailStrategy.triggerRecords.failTab'),
              children: (
                <Spin spinning={triggersLoading}>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={triggerTab === 'fail' ? triggers : []}
                    locale={{
                      emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('cryptoTailStrategy.triggerRecords.emptyFail')} />
                    }}
                    columns={[
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerTime'),
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 172,
                        render: (ts: number) => <Typography.Text>{new Date(ts).toLocaleString()}</Typography.Text>
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.direction'),
                        dataIndex: 'outcomeIndex',
                        key: 'outcomeIndex',
                        width: 80,
                        align: 'center',
                        render: (i: number) =>
                          i === 0 ? (
                            <Tag icon={<ArrowUpOutlined />} color="green">{t('cryptoTailStrategy.triggerRecords.up')}</Tag>
                          ) : (
                            <Tag icon={<ArrowDownOutlined />} color="volcano">{t('cryptoTailStrategy.triggerRecords.down')}</Tag>
                          )
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.triggerPrice'),
                        dataIndex: 'triggerPrice',
                        key: 'triggerPrice',
                        width: 100,
                        render: (v: string) => (formatNumber(v, 2) || '-')
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.amount'),
                        dataIndex: 'amountUsdc',
                        key: 'amountUsdc',
                        width: 110,
                        render: (v: string) => `${formatUSDC(v)} USDC`
                      },
                      {
                        title: t('cryptoTailStrategy.triggerRecords.failReason'),
                        dataIndex: 'failReason',
                        key: 'failReason',
                        ellipsis: true,
                        render: (v: string | null | undefined) => {
                          const text = v ?? '-'
                          return text.length > 40 ? (
                            <Tooltip title={text}>
                              <Typography.Text ellipsis style={{ maxWidth: 280 }}>{text}</Typography.Text>
                            </Tooltip>
                          ) : (
                            <Typography.Text type={text === '-' ? 'secondary' : undefined}>{text}</Typography.Text>
                          )
                        }
                      }
                    ]}
                    pagination={{
                      current: triggerPage,
                      pageSize: triggerPageSize,
                      total: triggersTotal,
                      showSizeChanger: true,
                      showTotal: (total) => t('cryptoTailStrategy.triggerRecords.totalCount').replace('{count}', String(total)),
                      onChange: onTriggerPageChange
                    }}
                    scroll={{ x: 540 }}
                  />
                </Spin>
              )
            }
          ]}
        />
      </Modal>
    </div>
  )
}

export default CryptoTailStrategyList
