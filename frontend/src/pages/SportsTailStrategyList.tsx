import { useEffect, useState } from 'react'
import {
  Card,
  Table,
  Button,
  Space,
  message,
  Select,
  Modal,
  Form,
  Input,
  InputNumber,
  Radio,
  Spin,
  Popconfirm,
  Empty,
  Drawer,
  Row,
  Col,
  Typography
} from 'antd'
import dayjs from 'dayjs'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type {
  SportsTailStrategyDto,
  SportsTailStrategyCreateRequest,
  SportsTailTriggerDto,
  SportsCategoryDto,
  SportsMarketDto
} from '../types'
import { formatUSDC } from '../utils'

const POLYMARKET_BASE = 'https://polymarket.com/event/'

const SportsTailStrategyList: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [list, setList] = useState<SportsTailStrategyDto[]>([])
  const [loading, setLoading] = useState(false)
  const [filters, setFilters] = useState<{ accountId?: number; sport?: string }>({})
  const [formModalOpen, setFormModalOpen] = useState(false)
  const [sportsList, setSportsList] = useState<SportsCategoryDto[]>([])
  const [marketSearchLoading, setMarketSearchLoading] = useState(false)
  const [marketSearchResult, setMarketSearchResult] = useState<SportsMarketDto[]>([])
  const [marketSearchFilters, setMarketSearchFilters] = useState<{
    sport?: string
    keyword?: string
  }>({})
  const [recordsDrawerOpen, setRecordsDrawerOpen] = useState(false)
  const [records, setRecords] = useState<SportsTailTriggerDto[]>([])
  const [recordsTotal, setRecordsTotal] = useState(0)
  const [recordsLoading, setRecordsLoading] = useState(false)
  const [recordsPage, setRecordsPage] = useState(1)
  const [recordsPageSize] = useState(20)
  const [recordsFilters, setRecordsFilters] = useState<{
    accountId?: number
    status?: string
    startTime?: number
    endTime?: number
  }>({})
  const [form] = Form.useForm()

  useEffect(() => {
    fetchAccounts()
    fetchSportsList()
  }, [])

  useEffect(() => {
    fetchList()
  }, [filters])

  const fetchList = async () => {
    setLoading(true)
    try {
      const res = await apiService.sportsTailStrategy.list(filters)
      if (res.data.code === 0 && res.data.data?.list) {
        setList(res.data.data.list)
      } else {
        message.error(res.data.msg || t('sportsTailStrategy.list.fetchFailed'))
      }
    } catch (e) {
      message.error((e as Error).message || t('sportsTailStrategy.list.fetchFailed'))
    } finally {
      setLoading(false)
    }
  }

  const fetchSportsList = async () => {
    try {
      const res = await apiService.sportsTailStrategy.sportsList()
      if (res.data.code === 0 && res.data.data?.list) {
        setSportsList(res.data.data.list)
      }
    } catch {
      setSportsList([])
    }
  }

  const fetchMarketSearch = async () => {
    setMarketSearchLoading(true)
    try {
      const res = await apiService.sportsTailStrategy.marketSearch({
        sport: marketSearchFilters.sport || undefined,
        keyword: marketSearchFilters.keyword || undefined,
        limit: 50
      })
      if (res.data.code === 0 && res.data.data?.list) {
        setMarketSearchResult(res.data.data.list)
      } else {
        setMarketSearchResult([])
      }
    } catch {
      setMarketSearchResult([])
    } finally {
      setMarketSearchLoading(false)
    }
  }

  const fetchRecords = async (page = 1) => {
    setRecordsLoading(true)
    try {
      const res = await apiService.sportsTailStrategy.triggers({
        accountId: recordsFilters.accountId,
        status: recordsFilters.status,
        startTime: recordsFilters.startTime,
        endTime: recordsFilters.endTime,
        page,
        pageSize: recordsPageSize
      })
      if (res.data.code === 0 && res.data.data) {
        setRecords(res.data.data.list)
        setRecordsTotal(res.data.data.total)
        setRecordsPage(page)
      }
    } catch {
      setRecords([])
      setRecordsTotal(0)
    } finally {
      setRecordsLoading(false)
    }
  }

  const openAddModal = () => {
    form.resetFields()
    form.setFieldsValue({ amountMode: 'FIXED' })
    setFormModalOpen(true)
    setMarketSearchResult([])
    setMarketSearchFilters({})
    fetchSportsList()
  }

  const handleFormSubmit = async () => {
    try {
      const v = await form.validateFields()
      const payload: SportsTailStrategyCreateRequest = {
        accountId: v.accountId,
        conditionId: v.conditionId,
        marketTitle: v.marketTitle,
        eventSlug: v.eventSlug || undefined,
        triggerPrice: String(v.triggerPrice),
        amountMode: v.amountMode,
        amountValue: String(v.amountValue),
        takeProfitPrice: v.takeProfitPrice != null ? String(v.takeProfitPrice) : undefined,
        stopLossPrice: v.stopLossPrice != null ? String(v.stopLossPrice) : undefined
      }
      const res = await apiService.sportsTailStrategy.create(payload)
      if (res.data.code === 0) {
        message.success(t('sportsTailStrategy.form.createSuccess'))
        setFormModalOpen(false)
        fetchList()
      } else {
        message.error(res.data.msg || t('sportsTailStrategy.form.createFailed'))
      }
    } catch (e) {
      if (e && typeof (e as { errorFields?: unknown }).errorFields === 'undefined') {
        message.error((e as Error).message || t('sportsTailStrategy.form.createFailed'))
      }
    }
  }

  const handleDelete = async (id: number) => {
    try {
      const res = await apiService.sportsTailStrategy.delete({ id })
      if (res.data.code === 0) {
        message.success(t('message.success'))
        fetchList()
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const openRecordsDrawer = () => {
    setRecordsDrawerOpen(true)
    setRecordsFilters({})
    fetchRecords(1)
  }

  useEffect(() => {
    if (recordsDrawerOpen) {
      fetchRecords(recordsPage)
    }
  }, [recordsDrawerOpen, recordsFilters])

  const renderAmount = (row: SportsTailStrategyDto) => {
    if (row.amountMode === 'FIXED') {
      return `${formatUSDC(row.amountValue)} USDC`
    }
    return `${row.amountValue}%`
  }

  const renderTakeProfitStopLoss = (row: SportsTailStrategyDto) => {
    const a = row.takeProfitPrice != null ? formatUSDC(row.takeProfitPrice) : '-'
    const b = row.stopLossPrice != null ? formatUSDC(row.stopLossPrice) : '-'
    return `${a} / ${b}`
  }

  const renderFilledOrRealtime = (row: SportsTailStrategyDto) => {
    if (row.filled && row.filledPrice != null && row.filledOutcomeName != null && row.filledShares != null) {
      return `${formatUSDC(row.filledPrice)} ${row.filledOutcomeName} | ${formatUSDC(row.filledShares)} ${t('sportsTailStrategy.list.shares')}`
    }
    const yes = row.realtimeYesPrice != null ? formatUSDC(row.realtimeYesPrice) : '-'
    const no = row.realtimeNoPrice != null ? formatUSDC(row.realtimeNoPrice) : '-'
    return `${t('sportsTailStrategy.list.realtimePrice')}: ${yes} / ${no}`
  }

  const renderPnl = (row: SportsTailStrategyDto) => {
    if (row.sold && row.realizedPnl != null) {
      const n = parseFloat(row.realizedPnl)
      const prefix = n >= 0 ? '+' : ''
      return `${prefix}${formatUSDC(row.realizedPnl)} USDC`
    }
    if (row.filled && !row.sold) return t('sportsTailStrategy.list.pending')
    return '-'
  }

  const columns = [
    {
      title: t('sportsTailStrategy.list.triggerPrice'),
      dataIndex: 'triggerPrice',
      key: 'triggerPrice',
      render: (v: string) => `>= ${formatUSDC(v)}`
    },
    {
      title: t('sportsTailStrategy.list.amount'),
      key: 'amount',
      render: (_: unknown, row: SportsTailStrategyDto) => renderAmount(row)
    },
    {
      title: t('sportsTailStrategy.list.takeProfitStopLoss'),
      key: 'tpSl',
      render: (_: unknown, row: SportsTailStrategyDto) => renderTakeProfitStopLoss(row)
    },
    {
      title: t('sportsTailStrategy.list.filledPrice'),
      key: 'filled',
      render: (_: unknown, row: SportsTailStrategyDto) => renderFilledOrRealtime(row)
    },
    {
      title: t('sportsTailStrategy.list.pnl'),
      key: 'pnl',
      render: (_: unknown, row: SportsTailStrategyDto) => renderPnl(row)
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_: unknown, row: SportsTailStrategyDto) => (
        <Space>
          <Button type="link" size="small" onClick={openRecordsDrawer}>
            {t('sportsTailStrategy.list.viewRecords')}
          </Button>
          <Popconfirm
            title={t('sportsTailStrategy.list.deleteConfirm')}
            onConfirm={() => handleDelete(row.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>
              {t('sportsTailStrategy.list.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  const recordColumns = [
    {
      title: t('sportsTailStrategy.records.time'),
      dataIndex: 'triggeredAt',
      key: 'triggeredAt',
      render: (v: number) => dayjs(v).format('MM-DD HH:mm')
    },
    {
      title: t('sportsTailStrategy.records.market'),
      dataIndex: 'marketTitle',
      key: 'marketTitle'
    },
    {
      title: t('sportsTailStrategy.records.direction'),
      dataIndex: 'outcomeName',
      key: 'outcomeName'
    },
    {
      title: t('sportsTailStrategy.records.buyPrice'),
      dataIndex: 'buyPrice',
      key: 'buyPrice',
      render: (v: string) => formatUSDC(v)
    },
    {
      title: t('sportsTailStrategy.records.amount'),
      dataIndex: 'buyAmount',
      key: 'buyAmount',
      render: (v: string) => formatUSDC(v)
    },
    {
      title: t('sportsTailStrategy.records.sellPrice'),
      dataIndex: 'sellPrice',
      key: 'sellPrice',
      render: (v: string | null) => (v != null ? formatUSDC(v) : '-')
    },
    {
      title: t('sportsTailStrategy.records.pnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      render: (v: string | null) => {
        if (v == null) return t('sportsTailStrategy.records.pending')
        const n = parseFloat(v)
        const prefix = n >= 0 ? '+' : ''
        return `${prefix}${formatUSDC(v)} USDC`
      }
    }
  ]

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {t('sportsTailStrategy.list.title')}
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openAddModal}>
          {t('sportsTailStrategy.list.addStrategy')}
        </Button>
        <Space>
          <Select
            placeholder={t('sportsTailStrategy.list.filter.account')}
            allowClear
            style={{ minWidth: 140 }}
            value={filters.accountId}
            onChange={(v) => setFilters((prev) => ({ ...prev, accountId: v }))}
            options={accounts.map((a) => ({ label: a.accountName || a.proxyAddress?.slice(0, 8) + '...', value: a.id }))}
          />
          <Select
            placeholder={t('sportsTailStrategy.list.filter.category')}
            allowClear
            style={{ minWidth: 120 }}
            value={filters.sport}
            onChange={(v) => setFilters((prev) => ({ ...prev, sport: v }))}
            options={[{ label: t('sportsTailStrategy.list.filter.allCategory'), value: undefined }, ...sportsList.map((s) => ({ label: s.name || s.sport, value: s.sport }))]}
          />
        </Space>
      </div>

      <Spin spinning={loading}>
        {isMobile ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            {list.length === 0 ? (
              <Empty description={t('common.noData')} />
            ) : (
              list.map((row) => (
                <Card key={row.id} size="small" title={row.marketTitle}>
                  <Row gutter={[8, 8]}>
                    <Col span={24}>{t('sportsTailStrategy.list.filter.account')}: {row.accountName}</Col>
                    <Col span={24}>{t('sportsTailStrategy.list.triggerPrice')}: &gt;= {formatUSDC(row.triggerPrice)} | {t('sportsTailStrategy.list.amount')}: {renderAmount(row)}</Col>
                    <Col span={24}>{t('sportsTailStrategy.list.takeProfitStopLoss')}: {renderTakeProfitStopLoss(row)}</Col>
                    <Col span={24}>{renderFilledOrRealtime(row)}</Col>
                    <Col span={24}>{t('sportsTailStrategy.list.pnl')}: {renderPnl(row)}</Col>
                    <Col span={24}>
                      <Space>
                        <Button type="link" size="small" onClick={openRecordsDrawer}>{t('sportsTailStrategy.list.viewRecords')}</Button>
                        <Popconfirm
                          title={t('sportsTailStrategy.list.deleteConfirm')}
                          onConfirm={() => handleDelete(row.id)}
                          okText={t('common.confirm')}
                          cancelText={t('common.cancel')}
                        >
                          <Button type="link" danger size="small">{t('sportsTailStrategy.list.delete')}</Button>
                        </Popconfirm>
                      </Space>
                    </Col>
                  </Row>
                </Card>
              ))
            )}
          </Space>
        ) : (
          <Table
            rowKey="id"
            dataSource={list}
            columns={[
              {
                title: t('sportsTailStrategy.records.market'),
                dataIndex: 'marketTitle',
                key: 'marketTitle',
                ellipsis: true,
                render: (text: string, row: SportsTailStrategyDto) => {
                  const url = row.eventSlug ? `${POLYMARKET_BASE}${row.eventSlug}` : null
                  if (url) {
                    return <a href={url} target="_blank" rel="noopener noreferrer">{text}</a>
                  }
                  return text
                }
              },
              {
                title: t('sportsTailStrategy.list.filter.account'),
                dataIndex: 'accountName',
                key: 'accountName',
                width: 100
              },
              ...columns
            ]}
            pagination={false}
            locale={{ emptyText: t('common.noData') }}
          />
        )}
      </Spin>

      <Modal
        title={t('sportsTailStrategy.form.title')}
        open={formModalOpen}
        onCancel={() => setFormModalOpen(false)}
        onOk={handleFormSubmit}
        width={isMobile ? '100%' : 560}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="accountId" label={t('sportsTailStrategy.form.account')} rules={[{ required: true }]}>
            <Select
              placeholder={t('sportsTailStrategy.form.selectAccount')}
              options={accounts.map((a) => ({ label: a.accountName || a.proxyAddress, value: a.id }))}
            />
          </Form.Item>
          <Form.Item label={t('sportsTailStrategy.form.selectMarket')} required>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Space wrap>
                <Select
                  placeholder={t('sportsTailStrategy.marketSearch.sport')}
                  allowClear
                  style={{ minWidth: 120 }}
                  value={marketSearchFilters.sport}
                  onChange={(v) => setMarketSearchFilters((prev) => ({ ...prev, sport: v }))}
                  options={[{ label: t('sportsTailStrategy.marketSearch.all'), value: undefined }, ...sportsList.map((s) => ({ label: s.name || s.sport, value: s.sport }))]}
                />
                <Input
                  placeholder={t('sportsTailStrategy.marketSearch.keyword')}
                  style={{ width: 160 }}
                  value={marketSearchFilters.keyword}
                  onChange={(e) => setMarketSearchFilters((prev) => ({ ...prev, keyword: e.target.value }))}
                />
                <Button onClick={fetchMarketSearch} loading={marketSearchLoading}>{t('sportsTailStrategy.marketSearch.search')}</Button>
              </Space>
              <div style={{ maxHeight: 200, overflow: 'auto', border: '1px solid #d9d9d9', borderRadius: 6, padding: 8 }}>
                {marketSearchLoading ? (
                  <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div>
                ) : marketSearchResult.length === 0 ? (
                  <Empty description={t('common.noData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  <Radio.Group
                    style={{ width: '100%' }}
                    onChange={(e) => {
                      const c = e.target.value as SportsMarketDto
                      form.setFieldsValue({
                        conditionId: c.conditionId,
                        marketTitle: c.question,
                        eventSlug: c.eventSlug ?? undefined
                      })
                    }}
                  >
                    <Space direction="vertical" style={{ width: '100%' }}>
                      {marketSearchResult.map((m) => (
                        <Radio key={m.conditionId} value={m}>
                          <div>
                            <div>{m.question}</div>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {m.outcomes?.[0]}: {m.outcomePrices?.[0] ?? '-'} | {m.outcomes?.[1]}: {m.outcomePrices?.[1] ?? '-'} | {t('sportsTailStrategy.marketSearch.liquidity')}: {formatUSDC(m.liquidity)} USDC
                            </Typography.Text>
                          </div>
                        </Radio>
                      ))}
                    </Space>
                  </Radio.Group>
                )}
              </div>
            </Space>
          </Form.Item>
          <Form.Item name="conditionId" hidden rules={[{ required: true, message: t('sportsTailStrategy.form.selectMarket') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="marketTitle" hidden><Input /></Form.Item>
          <Form.Item name="eventSlug" hidden><Input /></Form.Item>
          <Form.Item
            name="triggerPrice"
            label={t('sportsTailStrategy.form.triggerCondition')}
            rules={[{ required: true }, { type: 'number', min: 0.01, max: 1 }]}
            extra={t('sportsTailStrategy.form.triggerPriceHelp')}
          >
            <InputNumber min={0.01} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.90" />
          </Form.Item>
          <Form.Item name="amountMode" label={t('sportsTailStrategy.form.amount')} rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="FIXED">{t('sportsTailStrategy.form.fixedAmount')} (USDC)</Radio>
              <Radio value="RATIO">{t('sportsTailStrategy.form.ratio')} (%)</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="amountValue"
            rules={[{ required: true }]}
            noStyle
          >
            <InputNumber min={0.01} step={0.1} style={{ width: 160 }} placeholder="10" />
          </Form.Item>
          <Form.Item name="takeProfitPrice" label={t('sportsTailStrategy.form.takeProfitPrice')} extra={t('sportsTailStrategy.form.takeProfitHelp')}>
            <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.98" />
          </Form.Item>
          <Form.Item name="stopLossPrice" label={t('sportsTailStrategy.form.stopLossPrice')} extra={t('sportsTailStrategy.form.stopLossHelp')}>
            <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} placeholder="0.85" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={t('sportsTailStrategy.records.title')}
        open={recordsDrawerOpen}
        onClose={() => setRecordsDrawerOpen(false)}
        width={isMobile ? '100%' : 720}
      >
        <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
          <Select
            placeholder={t('sportsTailStrategy.list.filter.account')}
            allowClear
            style={{ minWidth: 160 }}
            value={recordsFilters.accountId}
            onChange={(v) => setRecordsFilters((prev) => ({ ...prev, accountId: v }))}
            options={accounts.map((a) => ({ label: a.accountName || a.proxyAddress?.slice(0, 8) + '...', value: a.id }))}
          />
          <Button onClick={() => fetchRecords(1)} loading={recordsLoading}>{t('common.refresh')}</Button>
        </Space>
        <Table
          rowKey="id"
          dataSource={records}
          columns={recordColumns}
          loading={recordsLoading}
          pagination={{
            current: recordsPage,
            pageSize: recordsPageSize,
            total: recordsTotal,
            showSizeChanger: false,
            onChange: (p) => fetchRecords(p)
          }}
          size="small"
          locale={{ emptyText: t('common.noData') }}
        />
      </Drawer>
    </div>
  )
}

export default SportsTailStrategyList
