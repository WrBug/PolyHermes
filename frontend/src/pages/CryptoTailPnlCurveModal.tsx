import { useEffect, useRef } from 'react'
import { Modal, Row, Col, Statistic, Button, Space, Empty } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs from 'dayjs'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { formatUSDC } from '../utils'
import type { CryptoTailPnlCurveResponse } from '../types'

export interface CryptoTailPnlCurveModalProps {
  open: boolean
  onClose: () => void
  data: CryptoTailPnlCurveResponse | null
  loading: boolean
  strategyName: string
  preset: 'today' | '7d' | '30d' | 'all'
  onPresetChange: (preset: 'today' | '7d' | '30d' | 'all') => void
  onRefresh: () => void
}

const CryptoTailPnlCurveModal: React.FC<CryptoTailPnlCurveModalProps> = (props) => {
  const { open, onClose, data, loading, strategyName, preset, onPresetChange, onRefresh } = props
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!open || !data?.curveData?.length || !chartRef.current) return
    if (chartInstance.current) {
      const dom = chartInstance.current.getDom()
      if (!dom || !document.contains(dom)) {
        chartInstance.current.dispose()
        chartInstance.current = null
      }
    }
    if (!chartInstance.current) chartInstance.current = echarts.init(chartRef.current)
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ value: [number, number] }>
          const v = arr[0]?.value
          if (!v) return ''
          const d = data.curveData.find((p) => p.timestamp === v[0])
          if (!d) return ''
          return dayjs(v[0]).format('YYYY-MM-DD HH:mm') + '<br/>' + t('cryptoTailStrategy.pnlCurve.totalPnl') + ': ' + formatUSDC(d.cumulativePnl) + ' USDC'
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '10%', containLabel: true },
      xAxis: { type: 'time' },
      yAxis: { type: 'value', axisLabel: { formatter: (val: number) => String(val) + ' USDC' } },
      series: [{
        name: t('cryptoTailStrategy.pnlCurve.totalPnl'),
        type: 'line',
        data: data.curveData.map((p) => [p.timestamp, parseFloat(p.cumulativePnl)]),
        smooth: preset === 'all',
        symbol: 'circle',
        symbolSize: 4,
        lineStyle: { width: 2, color: '#1890ff' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(24, 144, 255, 0.3)' },
            { offset: 1, color: 'rgba(24, 144, 255, 0.05)' }
          ])
        }
      }]
    }
    chartInstance.current.setOption(option, true)
    chartInstance.current.resize()
  }, [open, data, preset, t])

  useEffect(() => {
    if (!open) {
      chartInstance.current?.dispose()
      chartInstance.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open || !chartInstance.current) return
    const handleResize = () => chartInstance.current?.resize()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [open])

  const pnlColor = (value: string | null | undefined): string | undefined => {
    if (value == null || value === '') return undefined
    const num = parseFloat(value)
    if (Number.isNaN(num)) return undefined
    if (num > 0) return '#52c41a'
    if (num < 0) return '#ff4d4f'
    return undefined
  }

  return (
    <Modal
      title={t('cryptoTailStrategy.pnlCurve.title') + ' - ' + strategyName}
      open={open}
      onCancel={onClose}
      footer={null}
      width={Math.min(800, window.innerWidth - 48)}
      destroyOnClose
    >
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Statistic
            title={t('cryptoTailStrategy.pnlCurve.totalPnl')}
            value={data?.totalRealizedPnl != null ? formatUSDC(data.totalRealizedPnl) : '-'}
            suffix="USDC"
            valueStyle={{ color: pnlColor(data?.totalRealizedPnl ?? null) }}
          />
        </Col>
        <Col xs={12} sm={6}>
          <Statistic title={t('cryptoTailStrategy.pnlCurve.settledCount')} value={data?.settledCount ?? 0} />
        </Col>
        <Col xs={12} sm={6}>
          <Statistic
            title={t('cryptoTailStrategy.pnlCurve.winRate')}
            value={data?.winRate != null ? (Number(data.winRate) * 100).toFixed(1) + '%' : '-'}
          />
        </Col>
        <Col xs={12} sm={6}>
          <Statistic
            title={t('cryptoTailStrategy.pnlCurve.maxDrawdown')}
            value={data?.maxDrawdown != null ? '-' + formatUSDC(data.maxDrawdown) : '-'}
            suffix="USDC"
            valueStyle={{ color: data?.maxDrawdown ? '#ff4d4f' : undefined }}
          />
        </Col>
      </Row>
      <Space wrap style={{ marginBottom: 16 }}>
        <Button size="small" type={preset === 'today' ? 'primary' : 'default'} onClick={() => onPresetChange('today')}>{t('cryptoTailStrategy.pnlCurve.today')}</Button>
        <Button size="small" type={preset === '7d' ? 'primary' : 'default'} onClick={() => onPresetChange('7d')}>{t('cryptoTailStrategy.pnlCurve.last7Days')}</Button>
        <Button size="small" type={preset === '30d' ? 'primary' : 'default'} onClick={() => onPresetChange('30d')}>{t('cryptoTailStrategy.pnlCurve.last30Days')}</Button>
        <Button size="small" type={preset === 'all' ? 'primary' : 'default'} onClick={() => onPresetChange('all')}>{t('cryptoTailStrategy.pnlCurve.all')}</Button>
        <Button size="small" onClick={onRefresh} loading={loading}>{t('common.refresh')}</Button>
      </Space>
      {!data && loading
        ? <div style={{ height: 320, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{t('common.loading')}</div>
        : !data?.curveData?.length
          ? <Empty description={t('cryptoTailStrategy.pnlCurve.empty')} style={{ marginTop: 24 }} />
          : <div ref={chartRef} style={{ width: '100%', height: isMobile ? 260 : 320 }} />}
    </Modal>
  )
}

export default CryptoTailPnlCurveModal
