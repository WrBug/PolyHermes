import { useEffect, useState, useRef, useCallback } from 'react'
import {
  Card,
  Select,
  Space,
  Statistic,
  Row,
  Col,
  Typography,
  Spin,
  Empty,
  Alert
} from 'antd'
import { ClockCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { apiService } from '../services/api'
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import { formatNumber } from '../utils'
import type {
  CryptoTailStrategyDto,
  CryptoTailMonitorInitResponse,
  CryptoTailMonitorPushData
} from '../types'

const { Title, Text } = Typography

/** 分时图数据点：时间戳、BTC 价格 USDC、市场 Up/Down 价格 0-1 */
interface PriceDataPoint {
  time: number
  btcPrice: number | null
  marketPriceUp: number | null
  marketPriceDown: number | null
}

const CryptoTailMonitor: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  // 策略列表
  const [strategies, setStrategies] = useState<CryptoTailStrategyDto[]>([])
  const [strategiesLoading, setStrategiesLoading] = useState(false)

  // 选中的策略
  const [selectedStrategyId, setSelectedStrategyId] = useState<number | null>(null)

  // 监控数据
  const [initData, setInitData] = useState<CryptoTailMonitorInitResponse | null>(null)
  const [pushData, setPushData] = useState<CryptoTailMonitorPushData | null>(null)
  const [initLoading, setInitLoading] = useState(false)

  // 价格历史数据（用于分时图）
  const [priceHistory, setPriceHistory] = useState<PriceDataPoint[]>([])
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)
  const marketChartRef = useRef<HTMLDivElement>(null)
  const marketChartInstance = useRef<echarts.ECharts | null>(null)
  const lastPeriodStartRef = useRef<number | null>(null)
  // 记录首次数据进入时间（用于中途进入时的横轴起点）
  const [firstDataTime, setFirstDataTime] = useState<number | null>(null)
  // 标记是否已切换过周期（切换后使用完整周期）
  const [hasSwitchedPeriod, setHasSwitchedPeriod] = useState<boolean>(false)

  // 获取策略列表
  useEffect(() => {
    const fetchStrategies = async () => {
      setStrategiesLoading(true)
      try {
        const res = await apiService.cryptoTailStrategy.list({ enabled: true })
        if (res.data.code === 0 && res.data.data) {
          setStrategies(res.data.data.list ?? [])
          // 自动选择第一个策略
          if (res.data.data.list?.length > 0 && !selectedStrategyId) {
            setSelectedStrategyId(res.data.data.list[0].id)
          }
        }
      } catch (e) {
        console.error('Failed to fetch strategies:', e)
      } finally {
        setStrategiesLoading(false)
      }
    }
    fetchStrategies()
  }, [])

  // 初始化监控数据
  useEffect(() => {
    if (!selectedStrategyId) {
      setInitData(null)
      setPushData(null)
      setPriceHistory([])
      setFirstDataTime(null)
      setHasSwitchedPeriod(false)
      return
    }

    const initMonitor = async () => {
      setInitLoading(true)
      setPriceHistory([])
      setFirstDataTime(null)
      setHasSwitchedPeriod(false)
      try {
        const res = await apiService.cryptoTailStrategy.monitorInit(selectedStrategyId)
        if (res.data.code === 0 && res.data.data) {
          setInitData(res.data.data)
        } else {
          setInitData(null)
        }
      } catch (e) {
        console.error('Failed to init monitor:', e)
        setInitData(null)
      } finally {
        setInitLoading(false)
      }
    }
    initMonitor()
  }, [selectedStrategyId])

  // WebSocket 订阅
  const handlePushData = useCallback((data: CryptoTailMonitorPushData) => {
    if (data.strategyId !== selectedStrategyId) return
    setPushData(data)

    const btcPrice = data.currentPriceBtc != null && data.currentPriceBtc !== ''
      ? parseFloat(data.currentPriceBtc)
      : null
    const marketUp = data.currentPriceUp != null && data.currentPriceUp !== ''
      ? parseFloat(data.currentPriceUp)
      : null
    const marketDown = data.currentPriceDown != null && data.currentPriceDown !== ''
      ? parseFloat(data.currentPriceDown)
      : null
    const hasBtc = btcPrice != null && !Number.isNaN(btcPrice)
    const hasMarket = (marketUp != null && !Number.isNaN(marketUp)) || (marketDown != null && !Number.isNaN(marketDown))
    if (!hasBtc && !hasMarket) return

    const newPoint: PriceDataPoint = {
      time: data.timestamp,
      btcPrice: hasBtc ? btcPrice : null,
      marketPriceUp: hasMarket && marketUp != null && !Number.isNaN(marketUp) ? marketUp : null,
      marketPriceDown: hasMarket && marketDown != null && !Number.isNaN(marketDown) ? marketDown : null
    }

    // 用 ref 检测周期切换，避免因依赖 initData 导致回调频繁重建
    const pushPeriod = data.periodStartUnix
    const lastPeriod = lastPeriodStartRef.current

    if (pushPeriod != null && pushPeriod !== lastPeriod) {
      // 新周期或首次推送：更新 ref 和 initData，清空历史
      lastPeriodStartRef.current = pushPeriod
      // 如果之前已经有周期数据，说明是周期切换，标记为已切换
      if (lastPeriod != null) {
        setHasSwitchedPeriod(true)
      }
      // 周期切换时重置首次数据时间
      setFirstDataTime(newPoint.time)
      setInitData(prev => prev ? { ...prev, periodStartUnix: pushPeriod } : null)
      setPriceHistory([newPoint])
    } else {
      // 同周期：追加数据
      // 记录首次数据时间（仅在中途进入且未切换过周期时记录）
      setFirstDataTime(prev => {
        if (prev == null) {
          return newPoint.time
        }
        return prev
      })
      setPriceHistory(prev => {
        const maxPoints = 300
        const newHistory = [...prev, newPoint]
        return newHistory.slice(-maxPoints)
      })
    }
  }, [selectedStrategyId])

  const channel = selectedStrategyId ? `crypto_tail_monitor_${selectedStrategyId}` : ''
  useWebSocketSubscription(channel, handlePushData)

  // 图表容器仅在 initData 存在时渲染，故在更新图表时懒初始化
  useEffect(() => {
    const handleResize = () => {
      chartInstance.current?.resize()
      marketChartInstance.current?.resize()
    }
    window.addEventListener('resize', handleResize)
    return () => {
      window.removeEventListener('resize', handleResize)
      chartInstance.current?.dispose()
      chartInstance.current = null
      marketChartInstance.current?.dispose()
      marketChartInstance.current = null
    }
  }, [])

  // 更新图表：分时图为 BTC 价格 USDC
  useEffect(() => {
    if (!initData) return
    if (chartRef.current && !chartInstance.current) {
      chartInstance.current = echarts.init(chartRef.current)
    }
    if (!chartInstance.current) return

    const periodStartMs = (initData.periodStartUnix ?? 0) * 1000
    const periodEndMs = periodStartMs + (initData.intervalSeconds ?? 300) * 1000

    // data.timestamp 为毫秒，firstDataTime 已是 ms，无需再乘 1000
    const firstDataMs = firstDataTime != null ? firstDataTime : null
    const isMidEntry = firstDataMs != null && !hasSwitchedPeriod && firstDataMs > periodStartMs
    // 中途进入时横轴起点为进入时刻，否则为周期起点
    const xAxisMin = isMidEntry ? firstDataMs : periodStartMs

    const btcData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [p.time, p.btcPrice])
      : []
    const openBtc = pushData?.openPriceBtc ?? initData.openPriceBtc
    const openBtcNum = openBtc != null ? parseFloat(openBtc) : null

    const hasAnyBtcData = btcData.some(([, v]) => v != null && !Number.isNaN(v))
    const btcPlaceholderTime = xAxisMin
    const displayBtcData: [number, number | null][] = hasAnyBtcData
      ? btcData
      : (openBtcNum != null ? [[btcPlaceholderTime, openBtcNum]] : [])
    const minSpreadUpRaw = pushData?.minSpreadLineUp ?? initData.autoMinSpreadUp
    const minSpreadDownRaw = pushData?.minSpreadLineDown ?? initData.autoMinSpreadDown
    const minSpreadUp = minSpreadUpRaw != null && minSpreadUpRaw !== '' ? parseFloat(minSpreadUpRaw) : null
    const minSpreadDown = minSpreadDownRaw != null && minSpreadDownRaw !== '' ? parseFloat(minSpreadDownRaw) : null

    const validPrices = displayBtcData.flatMap(([, v]) => (v != null && !Number.isNaN(v) ? [v] : []))
    const defaultRange = 500
    let yMin: number | undefined
    let yMax: number | undefined
    if (validPrices.length > 0) {
      const dataMin = Math.min(...validPrices)
      const dataMax = Math.max(...validPrices)
      const dataRange = dataMax - dataMin
      const minRange = Math.max(Math.abs(dataMax) * 0.01, 10)
      const range = Math.max(dataRange, minRange)
      const padding = range * 0.25
      yMin = dataMin - padding
      yMax = dataMax + padding
    } else if (openBtcNum != null) {
      const spread = minSpreadUp ?? minSpreadDown ?? defaultRange
      const halfRange = spread * 1.5
      yMin = openBtcNum - halfRange
      yMax = openBtcNum + halfRange
    }

    const markLineData: Array<{ name: string; yAxis: number; lineStyle: { type: 'dashed'; color: string } }> = []
    if (openBtcNum != null && !Number.isNaN(openBtcNum)) {
      markLineData.push({
        name: t('cryptoTailMonitor.chart.openPrice'),
        yAxis: openBtcNum,
        lineStyle: { type: 'dashed', color: '#999' }
      })
    }
    if (openBtcNum != null && minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
      markLineData.push({
        name: t('cryptoTailMonitor.chart.minSpreadLine') + ' Up',
        yAxis: openBtcNum + minSpreadUp,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }
    if (openBtcNum != null && minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
      markLineData.push({
        name: t('cryptoTailMonitor.chart.minSpreadLine') + ' Down',
        yAxis: openBtcNum - minSpreadDown,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }

    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string; value: number | [number, number] }>
          const priceParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.price'))
          if (!priceParam) return ''
          const val = Array.isArray(priceParam.value) ? priceParam.value[1] : priceParam.value
          if (val == null || Number.isNaN(val)) return ''
          const ts = priceParam.name as unknown as number
          const offsetSec = Math.floor(ts / 1000) - (initData?.periodStartUnix ?? 0)
          const mins = Math.floor(offsetSec / 60)
          const secs = offsetSec % 60
          const timeStr = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          return `
            <div>
              <div>${t('cryptoTailMonitor.chart.time')}: ${timeStr}</div>
              <div>${t('cryptoTailMonitor.chart.price')}: ${Number(val).toFixed(2)} USDC</div>
            </div>
          `
        }
      },
      legend: {
        show: true,
        top: 0
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '12%',
        containLabel: true
      },
      xAxis: {
        type: 'time',
        min: xAxisMin,
        max: periodEndMs,
        boundaryGap: false,
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - (initData.periodStartUnix ?? 0)
            const mins = Math.floor(offsetSec / 60)
            const secs = offsetSec % 60
            return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          }
        }
      },
      yAxis: {
        type: 'value',
        scale: true,
        min: yMin,
        max: yMax,
        axisLabel: {
          formatter: (value: number) => value.toFixed(0)
        }
      },
      series: [
        {
          name: t('cryptoTailMonitor.chart.price'),
          type: 'line',
          data: displayBtcData,
          smooth: true,
          symbol: displayBtcData.length === 1 ? 'circle' : 'none',
          symbolSize: 4,
          lineStyle: { width: 2, color: '#1890ff' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(24, 144, 255, 0.3)' },
                { offset: 1, color: 'rgba(24, 144, 255, 0.05)' }
              ]
            }
          },
          markLine: markLineData.length > 0 ? { data: markLineData } : undefined
        }
      ]
    }

    chartInstance.current.setOption(option, true)
    chartInstance.current.resize()
  }, [priceHistory, initData, pushData, firstDataTime, hasSwitchedPeriod, t])

  // 更新市场分时图：Polymarket 价格 0-1
  useEffect(() => {
    if (!initData) return
    if (marketChartRef.current && !marketChartInstance.current) {
      marketChartInstance.current = echarts.init(marketChartRef.current)
    }
    if (!marketChartInstance.current) return

    const periodStartMs = (initData.periodStartUnix ?? 0) * 1000
    const periodEndMs = periodStartMs + (initData.intervalSeconds ?? 300) * 1000

    // data.timestamp 为毫秒，firstDataTime 已是 ms，无需再乘 1000
    const firstDataMs = firstDataTime != null ? firstDataTime : null
    const isMidEntry = firstDataMs != null && !hasSwitchedPeriod && firstDataMs > periodStartMs
    const xAxisMin = isMidEntry ? firstDataMs : periodStartMs

    const toMs = (t: number) => (t > 0 && t < 1e12 ? t * 1000 : t)
    const marketUpData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceUp])
      : []
    const marketDownData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceDown])
      : []

    const minPrice = parseFloat(initData.minPrice)
    const maxPrice = parseFloat(initData.maxPrice)
    const midPrice = (minPrice + maxPrice) / 2
    const isValid = (v: number | null): v is number => v != null && !Number.isNaN(v)
    const validUp: [number, number][] = marketUpData.filter((point): point is [number, number] => isValid(point[1]))
    const validDown: [number, number][] = marketDownData.filter((point): point is [number, number] => isValid(point[1]))
    const hasAnyMarketData = validUp.length > 0 || validDown.length > 0
    const placeholderTime = xAxisMin
    const finalMarketUp: [number, number][] = hasAnyMarketData ? validUp : [[placeholderTime, midPrice]]
    const finalMarketDown: [number, number][] = hasAnyMarketData ? validDown : [[placeholderTime, midPrice]]

    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string | number; value: number | [number, number] }>
          const upParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketUp'))
          const downParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketDown'))
          const rawTime = arr[0]?.name
          const timeStr = typeof rawTime === 'number' && initData
            ? (() => {
                const offsetSec = Math.floor(rawTime / 1000) - (initData.periodStartUnix ?? 0)
                const mins = Math.floor(offsetSec / 60)
                const secs = offsetSec % 60
                return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
              })()
            : String(rawTime ?? '')
          let html = `<div><div>${t('cryptoTailMonitor.chart.time')}: ${timeStr}</div>`
          const upVal = Array.isArray(upParam?.value) ? upParam?.value[1] : upParam?.value
          const downVal = Array.isArray(downParam?.value) ? downParam?.value[1] : downParam?.value
          if (upVal != null && !Number.isNaN(upVal)) html += `<div>Up: ${Number(upVal).toFixed(4)}</div>`
          if (downVal != null && !Number.isNaN(downVal)) html += `<div>Down: ${Number(downVal).toFixed(4)}</div>`
          html += '</div>'
          return html
        }
      },
      legend: {
        show: true,
        top: 0,
        data: [t('cryptoTailMonitor.chart.marketUp'), t('cryptoTailMonitor.chart.marketDown')]
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '15%',
        containLabel: true
      },
      xAxis: {
        type: 'time',
        min: xAxisMin,
        max: periodEndMs,
        boundaryGap: false,
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - (initData.periodStartUnix ?? 0)
            const mins = Math.floor(offsetSec / 60)
            const secs = offsetSec % 60
            return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          }
        }
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 1,
        interval: 0.2,
        axisLabel: { formatter: (v: number) => v.toFixed(1) }
      },
      series: [
        {
          name: t('cryptoTailMonitor.chart.marketUp'),
          type: 'line',
          data: finalMarketUp,
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          showSymbol: true,
          connectNulls: true,
          lineStyle: { width: 2, color: '#1890ff' },
          itemStyle: { color: '#1890ff' },
          markArea: {
            silent: true,
            itemStyle: { color: 'rgba(82, 196, 26, 0.12)' },
            data: [[{ yAxis: minPrice }, { yAxis: maxPrice }]]
          }
        },
        {
          name: t('cryptoTailMonitor.chart.marketDown'),
          type: 'line',
          data: finalMarketDown,
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          showSymbol: true,
          connectNulls: true,
          lineStyle: { width: 2, color: '#fa8c16' },
          itemStyle: { color: '#fa8c16' }
        }
      ]
    }

    marketChartInstance.current.setOption(option, true)
    marketChartInstance.current.resize()
  }, [priceHistory, initData, firstDataTime, hasSwitchedPeriod, t])

  // 格式化剩余时间
  const formatRemainingTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  // 显示 BTC 价格（最新价、价差、开盘价均为 USDC）
  const openPrice = pushData?.openPriceBtc ?? initData?.openPriceBtc
  const currentPrice = pushData?.currentPriceBtc
  const currentSpread = pushData?.spreadBtc
  const minSpreadUpStr = pushData?.minSpreadLineUp ?? initData?.autoMinSpreadUp
  const minSpreadDownStr = pushData?.minSpreadLineDown ?? initData?.autoMinSpreadDown
  const minSpreadUpVal = minSpreadUpStr != null && minSpreadUpStr !== '' ? parseFloat(minSpreadUpStr) : null
  const minSpreadDownVal = minSpreadDownStr != null && minSpreadDownStr !== '' ? parseFloat(minSpreadDownStr) : null
  const minSpreadLineNum = [minSpreadUpVal, minSpreadDownVal].filter((v): v is number => v != null && !Number.isNaN(v))
  const spreadBelowThreshold = currentSpread != null && currentSpread !== '' && minSpreadLineNum.length > 0 &&
    parseFloat(currentSpread) < Math.min(...minSpreadLineNum)

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Title level={2} style={{ marginBottom: 16, fontSize: isMobile ? 20 : 24 }}>
        {t('cryptoTailMonitor.title')}
      </Title>

      {/* 顶部控制区 */}
      <Card style={{ marginBottom: 16 }}>
        <Space wrap size="middle">
          <Space>
            <Text strong>{t('cryptoTailMonitor.selectStrategy')}</Text>
            <Select
              style={{ minWidth: isMobile ? 200 : 300 }}
              loading={strategiesLoading}
              value={selectedStrategyId}
              onChange={(id) => setSelectedStrategyId(id)}
              placeholder={t('cryptoTailMonitor.selectStrategyPlaceholder')}
              options={strategies.map(s => ({
                label: `${s.name || s.marketSlugPrefix} (${s.intervalSeconds === 300 ? '5m' : '15m'})`,
                value: s.id
              }))}
            />
          </Space>
        </Space>
      </Card>

      {initLoading ? (
        <Spin spinning style={{ display: 'flex', justifyContent: 'center', padding: 100 }} />
      ) : !initData ? (
        <Empty description={t('cryptoTailMonitor.noData')} />
      ) : (
        <>
          {/* 状态卡片：最小宽度填满整行，间距 16 */}
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.openPrice')}
                  value={openPrice ? formatNumber(openPrice, 2) : '-'}
                  precision={2}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.currentPrice')}
                  value={currentPrice ? formatNumber(currentPrice, 2) : '-'}
                  precision={2}
                  valueStyle={{ color: isMobile ? undefined : '#1890ff' }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.spread')}
                  value={(() => {
                    if (currentSpread == null || currentSpread === '') return '-'
                    const num = parseFloat(currentSpread)
                    if (Number.isNaN(num)) return '-'
                    const formatted = formatNumber(currentSpread, 2)
                    return num >= 0 ? `+${formatted}` : formatted
                  })()}
                  precision={2}
                  valueStyle={{
                    color: spreadBelowThreshold ? '#ff4d4f' : undefined
                  }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={t('cryptoTailMonitor.stat.remainingTime')}
                  value={pushData ? formatRemainingTime(pushData.remainingSeconds) : '-'}
                  prefix={<ClockCircleOutlined />}
                  valueStyle={{
                    color: pushData && pushData.remainingSeconds < 60 ? '#ff4d4f' : undefined
                  }}
                />
              </Card>
            </Col>
            <Col flex="1" style={{ minWidth: 140 }}>
              <Card size="small" style={{ width: '100%', minWidth: 0 }}>
                <Statistic
                  title={(initData.spreadDirection ?? 'MIN') === 'MAX' ? t('cryptoTailMonitor.stat.configuredSpreadMax') : t('cryptoTailMonitor.stat.configuredSpreadMin')}
                  valueRender={() => {
                    const mode = initData.minSpreadMode ?? 'NONE'
                    if (mode === 'NONE') return <Text type="secondary">-</Text>
                    if (mode === 'FIXED') {
                      const v = initData.minSpreadValue
                      return v != null && v !== '' ? formatNumber(v, 2) : '-'
                    }
                    const up = minSpreadUpStr != null && minSpreadUpStr !== '' ? formatNumber(minSpreadUpStr, 2) : null
                    const down = minSpreadDownStr != null && minSpreadDownStr !== '' ? formatNumber(minSpreadDownStr, 2) : null
                    if (up == null && down == null) return <Text type="secondary">-</Text>
                    return (
                      <Text style={{ fontSize: 13, lineHeight: 1.4 }}>
                        {up != null && <span style={{ display: 'block' }}>Up: {up}</span>}
                        {down != null && <span style={{ display: 'block' }}>Down: {down}</span>}
                      </Text>
                    )
                  }}
                />
              </Card>
            </Col>
          </Row>

          {/* 价格区间提示 */}
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`${t('cryptoTailMonitor.priceRange')}: ${formatNumber(initData.minPrice, 2)} ~ ${formatNumber(initData.maxPrice, 2)} | ${t('cryptoTailMonitor.timeWindow')}: ${Math.floor(initData.windowStartSeconds / 60)}:${(initData.windowStartSeconds % 60).toString().padStart(2, '0')} ~ ${Math.floor(initData.windowEndSeconds / 60)}:${(initData.windowEndSeconds % 60).toString().padStart(2, '0')}`}
          />

          {/* BTC 分时图 */}
          <Card title={t('cryptoTailMonitor.chart.btcTitle')}>
            <div
              ref={chartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 市场分时图 */}
          <Card title={t('cryptoTailMonitor.chart.marketTitle')} style={{ marginTop: 16 }}>
            <div
              ref={marketChartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 策略信息 */}
          <Card title={t('cryptoTailMonitor.strategyInfo.title')} style={{ marginTop: 16 }}>
            <Row gutter={[16, 8]}>
              <Col span={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.market')}: </Text>
                <Text>{initData.marketTitle}</Text>
              </Col>
              <Col span={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.interval')}: </Text>
                <Text>{initData.intervalSeconds === 300 ? '5m' : '15m'}</Text>
              </Col>
              <Col span={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.account')}: </Text>
                <Text>{initData.accountName || `#${initData.accountId}`}</Text>
              </Col>
              <Col span={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.spreadMode')}: </Text>
                <Text>{initData.minSpreadMode}</Text>
                {initData.minSpreadMode === 'FIXED' && initData.minSpreadValue && (
                  <Text> ({formatNumber(initData.minSpreadValue, 4)})</Text>
                )}
              </Col>
            </Row>
          </Card>
        </>
      )}
    </div>
  )
}

export default CryptoTailMonitor
