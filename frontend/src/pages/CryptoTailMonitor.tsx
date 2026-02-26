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
  Alert,
  Radio,
  Button,
  Tooltip,
  Modal,
  InputNumber,
  message
} from 'antd'
import { ClockCircleOutlined, SyncOutlined, InfoCircleOutlined, ShoppingCartOutlined } from '@ant-design/icons'
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
  const selectedStrategyIdRef = useRef<number | null>(null)
  useEffect(() => {
    selectedStrategyIdRef.current = selectedStrategyId
  }, [selectedStrategyId])

  // localStorage key for period switch mode
  const PERIOD_SWITCH_MODE_KEY = 'cryptoTailMonitor_periodSwitchMode'

  // 记录首次数据进入时间（用于中途进入时的横轴起点）
  const [firstDataTime, setFirstDataTime] = useState<number | null>(null)
  // 标记是否已切换过周期（切换后使用完整周期）
  const [hasSwitchedPeriod, setHasSwitchedPeriod] = useState<boolean>(false)
  // 周期切换模式：auto（自动切换）| manual（手动切换），从 localStorage 读取缓存
  const [periodSwitchMode, setPeriodSwitchMode] = useState<'auto' | 'manual'>(() => {
    const cached = localStorage.getItem(PERIOD_SWITCH_MODE_KEY)
    return (cached === 'auto' || cached === 'manual') ? cached : 'auto'
  })
  // 手动模式下，存储最新周期的数据（用户未切换时）
  const [pendingPeriodData, setPendingPeriodData] = useState<{
    periodStartUnix: number
    priceHistory: PriceDataPoint[]
    initData: CryptoTailMonitorInitResponse | null
    pushData: CryptoTailMonitorPushData | null
  } | null>(null)
  // 标记当前是否在查看旧周期（手动模式下）
  const [isViewingOldPeriod, setIsViewingOldPeriod] = useState<boolean>(false)

  // 手动下单状态
  const [manualOrderModal, setManualOrderModal] = useState<{
    visible: boolean
    direction: 'UP' | 'DOWN'
    price: string
    size: string
    totalAmount: string
    bestBid: string
    availableBalance: string
  }>({
    visible: false,
    direction: 'UP',
    price: '',
    size: '',
    totalAmount: '',
    bestBid: '',
    availableBalance: ''
  })
  const [ordering, setOrdering] = useState(false)

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
      setPendingPeriodData(null)
      setIsViewingOldPeriod(false)
      return
    }

    const initMonitor = async () => {
      setInitLoading(true)
      setPriceHistory([])
      setFirstDataTime(null)
      setHasSwitchedPeriod(false)
      setPendingPeriodData(null)
      setIsViewingOldPeriod(false)
      try {
        const res = await apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId })
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
      // 新周期到来：重新拉取 init（含 tokenIds），再更新状态
      lastPeriodStartRef.current = pushPeriod
      const marketTitle = (data as { marketTitle?: string }).marketTitle
      const applyFreshInit = (fresh: CryptoTailMonitorInitResponse) => {
        if (selectedStrategyIdRef.current !== fresh.strategyId) return
        const merged: CryptoTailMonitorInitResponse = {
          ...fresh,
          periodStartUnix: pushPeriod,
          marketTitle: marketTitle ?? fresh.marketTitle ?? ''
        }
        if (periodSwitchMode === 'manual' && lastPeriod != null) {
          setPendingPeriodData(p => p ? { ...p, initData: merged } : null)
        } else {
          setInitData(merged)
        }
      }
      if (periodSwitchMode === 'manual' && lastPeriod != null) {
        setPendingPeriodData({
          periodStartUnix: pushPeriod,
          priceHistory: [newPoint],
          initData: null,
          pushData: data
        })
        setIsViewingOldPeriod(true)
        apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId!, periodStartUnix: pushPeriod }).then(res => {
          if (res.data?.code === 0 && res.data?.data) applyFreshInit(res.data.data)
          else {
            setInitData(prev => {
              if (prev) setPendingPeriodData(p => p ? { ...p, initData: { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } } : null)
              return prev ?? null
            })
          }
        }).catch(() => {
          setInitData(prev => {
            if (prev) setPendingPeriodData(p => p ? { ...p, initData: { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } } : null)
            return prev ?? null
          })
        })
      } else {
        if (lastPeriod != null) setHasSwitchedPeriod(true)
        setFirstDataTime(newPoint.time)
        setPriceHistory([newPoint])
        setPushData(data)
        setIsViewingOldPeriod(false)
        setPendingPeriodData(null)
        apiService.cryptoTailStrategy.monitorInit({ strategyId: selectedStrategyId!, periodStartUnix: pushPeriod }).then(res => {
          if (res.data?.code === 0 && res.data?.data) applyFreshInit(res.data.data)
          else setInitData(prev => prev ? { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } : null)
        }).catch(() => {
          setInitData(prev => prev ? { ...prev, periodStartUnix: pushPeriod, marketTitle: marketTitle ?? prev.marketTitle ?? '' } : null)
        })
      }
      return
    } else {
      // 同周期：追加数据
      if (periodSwitchMode === 'manual' && isViewingOldPeriod && pendingPeriodData) {
        // 手动模式下，更新 pending 数据
        const minIntervalMs = 1_000
        setPendingPeriodData(prev => {
          if (!prev) return null
          const lastTime = prev.priceHistory.length > 0 ? prev.priceHistory[prev.priceHistory.length - 1].time : 0
          if (prev.priceHistory.length > 0 && newPoint.time - lastTime < minIntervalMs) {
            return { ...prev, pushData: data }
          }
          const maxPoints = 300
          const newHistory = [...prev.priceHistory, newPoint].slice(-maxPoints)
          return { ...prev, priceHistory: newHistory, pushData: data }
        })
      } else {
        setFirstDataTime(prev => {
          if (prev == null) {
            return newPoint.time
          }
          return prev
        })
        const minIntervalMs = 1_000
        setPriceHistory(prev => {
          const lastTime = prev.length > 0 ? prev[prev.length - 1].time : 0
          if (prev.length > 0 && newPoint.time - lastTime < minIntervalMs) {
            return prev
          }
          const maxPoints = 300
          const newHistory = [...prev, newPoint]
          return newHistory.slice(-maxPoints)
        })
        setPushData(data)
      }
    }
  }, [selectedStrategyId, periodSwitchMode, isViewingOldPeriod, pendingPeriodData])

  // 手动切换到最新周期
  const handleSwitchToLatestPeriod = useCallback(() => {
    if (pendingPeriodData) {
      setPriceHistory(pendingPeriodData.priceHistory)
      setFirstDataTime(pendingPeriodData.priceHistory[0]?.time ?? null)
      if (pendingPeriodData.initData) {
        setInitData(pendingPeriodData.initData)
      }
      if (pendingPeriodData.pushData) {
        setPushData(pendingPeriodData.pushData)
      }
      setHasSwitchedPeriod(true)
      setIsViewingOldPeriod(false)
      setPendingPeriodData(null)
    }
  }, [pendingPeriodData])

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

  // 切换策略时销毁并重新初始化图表实例
  useEffect(() => {
    if (chartInstance.current) {
      chartInstance.current.dispose()
      chartInstance.current = null
    }
    if (marketChartInstance.current) {
      marketChartInstance.current.dispose()
      marketChartInstance.current = null
    }
  }, [selectedStrategyId])

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

    const markLineData: Array<{ name?: string; yAxis?: number; xAxis?: number; lineStyle: { type: 'dashed' | 'solid'; color: string }; label?: { show: boolean; formatter?: string }; emphasis?: { label?: { show?: boolean; formatter?: string } } }> = []
    if (openBtcNum != null && !Number.isNaN(openBtcNum)) {
      markLineData.push({
        name: t('cryptoTailMonitor.chart.openPrice'),
        yAxis: openBtcNum,
        lineStyle: { type: 'dashed', color: '#999' }
      })
    }
    const isMaxSpread = (initData.spreadDirection ?? 'MIN') === 'MAX'
    const spreadLineLabelKey = isMaxSpread ? 'cryptoTailMonitor.chart.maxSpreadLine' : 'cryptoTailMonitor.chart.minSpreadLine'
    if (openBtcNum != null && minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
      markLineData.push({
        name: t(spreadLineLabelKey) + ' Up',
        yAxis: openBtcNum + minSpreadUp,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }
    if (openBtcNum != null && minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
      markLineData.push({
        name: t(spreadLineLabelKey) + ' Down',
        yAxis: openBtcNum - minSpreadDown,
        lineStyle: { type: 'dashed', color: '#ff4d4f' }
      })
    }
    // 时间窗口两条竖线：灰色虚线，悬停时显示标签
    const windowStartMs = periodStartMs + (initData.windowStartSeconds ?? 0) * 1000
    const windowEndMs = periodStartMs + (initData.windowEndSeconds ?? 0) * 1000
    if (windowStartMs > xAxisMin && windowStartMs < periodEndMs) {
      markLineData.push({
        xAxis: windowStartMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowStart') } }
      })
    }
    if (windowEndMs > xAxisMin && windowEndMs < periodEndMs && windowEndMs !== windowStartMs) {
      markLineData.push({
        xAxis: windowEndMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowEnd') } }
      })
    }

    const periodStartUnixSec = initData.periodStartUnix ?? 0
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        confine: true,
        padding: [6, 8],
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string | number; value: number | [number, number]; axisValue?: number }>
          const priceParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.price'))
          if (!priceParam) return ''
          const val = Array.isArray(priceParam.value) ? priceParam.value[1] : priceParam.value
          if (val == null || Number.isNaN(val)) return ''
          // 优先从 value[0] 取时间戳（毫秒），否则用 axisValue 或 name
          const rawTime = Array.isArray(priceParam.value)
            ? priceParam.value[0]
            : (priceParam.axisValue ?? priceParam.name)
          let timeStr = ''
          if (typeof rawTime === 'number' && !Number.isNaN(rawTime)) {
            const offsetSec = Math.floor(rawTime / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            timeStr = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          } else if (rawTime != null && rawTime !== '') {
            timeStr = String(rawTime)
          } else {
            timeStr = '--'
          }
          return `<span style="font-size:12px">${timeStr} &nbsp; ${Number(val).toFixed(2)} USDC</span>`
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
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
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
          markLine: markLineData.length > 0 ? { symbol: ['none', 'none'], data: markLineData } : undefined,
          // 添加满足条件的价差区域（浅绿色背景）
          markArea: (() => {
            if (openBtcNum == null) return undefined
            const areas: Array<[{ yAxis: number }, { yAxis: number }]> = []
            if (isMaxSpread) {
              // 最大价差：价差 <= 配置值触发，满足条件为靠近开盘价的带状区域
              if (minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
                areas.push([
                  { yAxis: openBtcNum },
                  { yAxis: openBtcNum + minSpreadUp }
                ])
              }
              if (minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
                areas.push([
                  { yAxis: openBtcNum - minSpreadDown },
                  { yAxis: openBtcNum }
                ])
              }
            } else {
              // 最小价差：价差 >= 配置值触发，满足条件为远离开盘价的两侧
              if (minSpreadUp != null && !Number.isNaN(minSpreadUp)) {
                areas.push([
                  { yAxis: openBtcNum + minSpreadUp },
                  { yAxis: yMax ?? openBtcNum + minSpreadUp * 2 }
                ])
              }
              if (minSpreadDown != null && !Number.isNaN(minSpreadDown)) {
                areas.push([
                  { yAxis: yMin ?? openBtcNum - minSpreadDown * 2 },
                  { yAxis: openBtcNum - minSpreadDown }
                ])
              }
            }
            return areas.length > 0 ? {
              silent: true,
              data: areas,
              itemStyle: { color: 'rgba(82, 196, 26, 0.12)' }
            } : undefined
          })()
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
    let marketUpData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceUp])
      : []
    let marketDownData: [number, number | null][] = priceHistory.length > 0
      ? priceHistory.map(p => [toMs(p.time), p.marketPriceDown])
      : []

    // 若推送有最新价且与当前周期一致，追加到末端使曲线显示到最新价格
    if (pushData && pushData.periodStartUnix === (initData.periodStartUnix ?? 0)) {
      const ts = pushData.timestamp
      const lastTime = marketUpData.length > 0 ? marketUpData[marketUpData.length - 1][0] : 0
      const tsMs = ts > 0 && ts < 1e12 ? ts * 1000 : ts
      if (tsMs >= lastTime) {
        const up = pushData.currentPriceUp != null && pushData.currentPriceUp !== '' ? parseFloat(pushData.currentPriceUp) : null
        const down = pushData.currentPriceDown != null && pushData.currentPriceDown !== '' ? parseFloat(pushData.currentPriceDown) : null
        const upVal = up != null && !Number.isNaN(up) ? up : (down != null && !Number.isNaN(down) ? 1 - down : null)
        const downVal = down != null && !Number.isNaN(down) ? down : (up != null && !Number.isNaN(up) ? 1 - up : null)
        if (upVal != null) marketUpData = [...marketUpData, [tsMs, upVal]]
        if (downVal != null) marketDownData = [...marketDownData, [tsMs, downVal]]
      }
    }

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

    const periodStartUnixSec = initData.periodStartUnix ?? 0
    const windowStartMs = periodStartMs + (initData.windowStartSeconds ?? 0) * 1000
    const windowEndMs = periodStartMs + (initData.windowEndSeconds ?? 0) * 1000
    const timeWindowMarkLine: Array<{ xAxis: number; lineStyle: { type: 'dashed'; color: string }; label: { show: boolean }; emphasis: { label: { show: boolean; formatter: string } } }> = []
    if (windowStartMs > xAxisMin && windowStartMs < periodEndMs) {
      timeWindowMarkLine.push({
        xAxis: windowStartMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowStart') } }
      })
    }
    if (windowEndMs > xAxisMin && windowEndMs < periodEndMs && windowEndMs !== windowStartMs) {
      timeWindowMarkLine.push({
        xAxis: windowEndMs,
        lineStyle: { type: 'dashed', color: 'rgba(128, 128, 128, 0.9)' },
        label: { show: false },
        emphasis: { label: { show: true, formatter: t('cryptoTailMonitor.chart.timeWindowEnd') } }
      })
    }
    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const arr = params as Array<{ seriesName: string; name: string | number; value: number | [number, number]; axisValue?: number }>
          const upParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketUp'))
          const downParam = arr.find(p => p.seriesName === t('cryptoTailMonitor.chart.marketDown'))
          const firstParam = arr[0]
          const rawTime = firstParam && Array.isArray(firstParam.value)
            ? firstParam.value[0]
            : (firstParam?.axisValue ?? firstParam?.name)
          let timeStr = ''
          if (typeof rawTime === 'number' && !Number.isNaN(rawTime)) {
            const offsetSec = Math.floor(rawTime / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
            timeStr = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
          } else if (rawTime != null && rawTime !== '') {
            timeStr = String(rawTime)
          } else {
            timeStr = '--'
          }
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
        axisLabel: {
          formatter: (val: number) => {
            const offsetSec = Math.floor(val / 1000) - periodStartUnixSec
            const mins = Math.floor(offsetSec / 60)
            const secs = Math.abs(offsetSec) % 60
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
          },
          markLine: timeWindowMarkLine.length > 0 ? { symbol: ['none', 'none'], data: timeWindowMarkLine } : undefined
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
  }, [priceHistory, initData, pushData, firstDataTime, hasSwitchedPeriod, t])

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

  // 手动下单：打开弹窗
  const handleOpenManualOrderModal = async (direction: 'UP' | 'DOWN') => {
    if (!pushData) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const bestBid = direction === 'UP' ? pushData.currentPriceUp : pushData.currentPriceDown
    if (!bestBid) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    // 计算默认价格：最优 bid × 1.1，限制在 0~1 之间
    const rawPrice = parseFloat(bestBid) * 1.1
    const defaultPrice = Math.min(1, Math.max(0, rawPrice))
    
    // 获取账户余额
    let availableBalance = '0'
    if (initData?.accountId) {
      try {
        const balanceRes = await apiService.accounts.balance({ accountId: initData.accountId })
        if (balanceRes.data.code === 0 && balanceRes.data.data?.availableBalance) {
          availableBalance = balanceRes.data.data.availableBalance
        }
      } catch (e) {
        console.error('获取账户余额失败:', e)
      }
    }
    
    // 使用策略配置的金额
    let defaultAmountUsdc = 10
    if (initData?.amountMode === 'FIXED' && initData?.amountValue) {
      defaultAmountUsdc = parseFloat(initData.amountValue)
    } else if (initData?.amountMode === 'RATIO' && initData?.amountValue) {
      // RATIO 模式：按比例计算
      const balanceNum = parseFloat(availableBalance)
      const ratio = parseFloat(initData.amountValue || '10')
      defaultAmountUsdc = Math.floor(balanceNum * ratio / 100)
      // 至少保留 1 USDC
      if (defaultAmountUsdc < 1) {
        message.warning(t('cryptoTailMonitor.manualOrder.insufficientBalance'))
        return
      }
    }
    
    // 计算默认数量（保留2位小数，用于手动下单）
    let defaultSize = (defaultAmountUsdc / defaultPrice).toFixed(2)
    // 确保至少 1 张
    if (parseFloat(defaultSize) < 1) {
      defaultSize = '1.00'
    }
    
    // 重新计算总金额（基于实际数量）
    const defaultTotalAmount = (defaultPrice * parseFloat(defaultSize)).toFixed(2)
    
    setManualOrderModal({
      visible: true,
      direction,
      price: defaultPrice.toFixed(4),
      size: defaultSize,
      totalAmount: defaultTotalAmount,
      bestBid,
      availableBalance
    })
  }

  // 获取最新价
  const handleFetchLatestPrice = async () => {
    if (!pushData) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const latestPrice = manualOrderModal.direction === 'UP' 
      ? pushData.currentPriceUp 
      : pushData.currentPriceDown
    if (!latestPrice) {
      message.warning(t('cryptoTailMonitor.manualOrder.priceNotLoaded'))
      return
    }
    const price = parseFloat(latestPrice)
    const size = parseFloat(manualOrderModal.size)
    const totalAmount = (price * size).toFixed(2)
    setManualOrderModal({ 
      ...manualOrderModal, 
      price: price.toFixed(4),
      totalAmount 
    })
    message.success(t('cryptoTailMonitor.manualOrder.priceUpdated'))
  }

  const handleCloseManualOrderModal = () => {
    setManualOrderModal({
      visible: false,
      direction: 'UP',
      price: '',
      size: '',
      totalAmount: '',
      bestBid: '',
      availableBalance: ''
    })
  }

  const handlePriceChange = (value: number | null) => {
    if (value === null) return
    const clamped = Math.min(1, Math.max(0, value))
    const price = clamped.toFixed(4)
    const size = parseFloat(manualOrderModal.size)
    const totalAmount = (clamped * size).toFixed(2)
    setManualOrderModal({ ...manualOrderModal, price, totalAmount })
  }

  const handleSizeChange = (value: number | null) => {
    if (value === null) return
    const size = value.toFixed(2)
    const priceRaw = parseFloat(manualOrderModal.price)
    const price = Math.min(1, Math.max(0, priceRaw))
    const totalAmount = (price * value).toFixed(2)
    setManualOrderModal({ ...manualOrderModal, size, totalAmount, price: price.toFixed(4) })
  }

  // 计算最大数量（截位处理）
  const handleMaxSize = () => {
    const price = parseFloat(manualOrderModal.price)
    const balance = parseFloat(manualOrderModal.availableBalance)
    
    if (price <= 0 || balance <= 0) {
      message.warning(t('cryptoTailMonitor.manualOrder.invalidPriceOrBalance'))
      return
    }
    
    // 最大数量 = Math.floor(可用余额 / 价格)
    const maxSize = Math.floor(balance / price)
    
    if (maxSize < 1) {
      message.warning(t('cryptoTailMonitor.manualOrder.insufficientBalanceForMax'))
      return
    }
    
    const totalAmount = (price * maxSize).toFixed(2)
    setManualOrderModal({ 
      ...manualOrderModal, 
      size: maxSize.toFixed(2),
      totalAmount 
    })
    message.success(t('cryptoTailMonitor.manualOrder.maxSizeUpdated'))
  }

  const handleManualOrder = async () => {
    if (!initData || !pushData) return
    try {
      setOrdering(true)
      const tokenIds: string[] = []
      if (initData.tokenIdUp) tokenIds.push(initData.tokenIdUp)
      if (initData.tokenIdDown) tokenIds.push(initData.tokenIdDown)
      const request = {
        strategyId: initData.strategyId,
        periodStartUnix: pushData.periodStartUnix,
        direction: manualOrderModal.direction,
        price: Math.min(1, Math.max(0, parseFloat(manualOrderModal.price) || 0)).toFixed(4),
        size: manualOrderModal.size,
        marketTitle: pushData.marketTitle || initData.marketTitle,
        tokenIds
      }
      const res = await apiService.cryptoTailStrategy.manualOrder(request)
      if (res.data.code === 0 && res.data.data?.success) {
        message.success(t('cryptoTailMonitor.manualOrder.success'))
        handleCloseManualOrderModal()
      } else {
        const reason = res.data.msg?.trim() || 'unknown'
        message.error(t('cryptoTailMonitor.manualOrder.failed', { reason }))
      }
    } catch (error: unknown) {
      const err = error as { response?: { data?: { msg?: string } }; message?: string }
      const reason = err?.response?.data?.msg?.trim() ?? err?.message?.trim() ?? 'unknown'
      message.error(t('cryptoTailMonitor.manualOrder.failed', { reason }))
    } finally {
      setOrdering(false)
    }
  }

  return (
    <div style={{ padding: isMobile ? 12 : 24 }}>
      <Title level={2} style={{ marginBottom: 16, fontSize: isMobile ? 20 : 24 }}>
        {t('cryptoTailMonitor.title')}
      </Title>

      {/* 顶部控制区 */}
      <Card style={{ marginBottom: 16 }}>
        <Space direction={isMobile ? 'vertical' : 'horizontal'} size="middle" style={{ width: '100%' }}>
          <Space direction={isMobile ? 'vertical' : 'horizontal'} size="small" style={{ width: isMobile ? '100%' : 'auto' }}>
            <Text strong>{t('cryptoTailMonitor.selectStrategy')}</Text>
            <Select
              style={{ minWidth: isMobile ? '100%' : 300, width: isMobile ? '100%' : 'auto' }}
              loading={strategiesLoading}
              value={selectedStrategyId}
              onChange={(id) => setSelectedStrategyId(id)}
              placeholder={t('cryptoTailMonitor.selectStrategyPlaceholder')}
              popupMatchSelectWidth={false}
              dropdownStyle={{ minWidth: isMobile ? 280 : 'auto', wordWrap: 'break-word', whiteSpace: 'normal' }}
              optionLabelProp="label"
              options={strategies.map(s => ({
                label: `${s.name || s.marketSlugPrefix} (${s.intervalSeconds === 300 ? '5m' : '15m'})`,
                value: s.id,
                style: { whiteSpace: 'normal', wordWrap: 'break-word' }
              }))}
            />
          </Space>
          {selectedStrategyId && (
            <Space>
              <Text strong>{t('cryptoTailMonitor.periodSwitch.mode')}</Text>
              <Radio.Group
                value={periodSwitchMode}
                onChange={(e) => {
                  const newMode = e.target.value
                  setPeriodSwitchMode(newMode)
                  localStorage.setItem(PERIOD_SWITCH_MODE_KEY, newMode)
                  if (newMode === 'auto' && isViewingOldPeriod && pendingPeriodData) {
                    handleSwitchToLatestPeriod()
                  }
                }}
                optionType="button"
                buttonStyle="solid"
                size="small"
              >
                <Tooltip title={t('cryptoTailMonitor.periodSwitch.autoDesc')}>
                  <Radio.Button value="auto">{t('cryptoTailMonitor.periodSwitch.auto')}</Radio.Button>
                </Tooltip>
                <Tooltip title={t('cryptoTailMonitor.periodSwitch.manualDesc')}>
                  <Radio.Button value="manual">{t('cryptoTailMonitor.periodSwitch.manual')}</Radio.Button>
                </Tooltip>
              </Radio.Group>
            </Space>
          )}
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

          {/* 手动模式下：周期结束提示 */}
          {periodSwitchMode === 'manual' && isViewingOldPeriod && (
            <Alert
              type="warning"
              showIcon
              icon={<InfoCircleOutlined />}
              style={{ marginBottom: 16 }}
              message={t('cryptoTailMonitor.periodSwitch.periodEnded')}
              description={
                <Space direction="vertical" size="small">
                  <Text>{t('cryptoTailMonitor.periodSwitch.newPeriodAvailable')}</Text>
                  <Button
                    type="primary"
                    icon={<SyncOutlined />}
                    onClick={handleSwitchToLatestPeriod}
                    size="small"
                  >
                    {t('cryptoTailMonitor.periodSwitch.switchToLatest')}
                  </Button>
                </Space>
              }
            />
          )}

          {/* 价格区间提示 */}
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`${t('cryptoTailMonitor.priceRange')}: ${formatNumber(initData.minPrice, 2)} ~ ${formatNumber(initData.maxPrice, 2)} | ${t('cryptoTailMonitor.timeWindow')}: ${Math.floor(initData.windowStartSeconds / 60)}:${(initData.windowStartSeconds % 60).toString().padStart(2, '0')} ~ ${Math.floor(initData.windowEndSeconds / 60)}:${(initData.windowEndSeconds % 60).toString().padStart(2, '0')}`}
          />

          {/* 价格分时图 */}
          <Card title={`${initData.marketSlugPrefix || 'BTC'} ${t('cryptoTailMonitor.chart.priceChart')}`}>
            <div
              ref={chartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 市场分时图 */}
          <Card
            title={t('cryptoTailMonitor.chart.marketTitle')}
            extra={
              pushData?.currentPriceUp != null || pushData?.currentPriceDown != null ? (
                <Space size="middle">
                  <Text type="secondary">{t('cryptoTailMonitor.chart.latestPrice')}:</Text>
                  {pushData.currentPriceUp != null && pushData.currentPriceUp !== '' && (
                    <Text>Up {formatNumber(pushData.currentPriceUp, 4)}</Text>
                  )}
                  {pushData.currentPriceDown != null && pushData.currentPriceDown !== '' && (
                    <Text>Down {formatNumber(pushData.currentPriceDown, 4)}</Text>
                  )}
                </Space>
              ) : null
            }
            style={{ marginTop: 16 }}
          >
            <div
              ref={marketChartRef}
              style={{
                width: '100%',
                height: isMobile ? 200 : 240
              }}
            />
          </Card>

          {/* 手动下单 */}
          {!isMobile ? (
            <Card title={t('cryptoTailMonitor.manualOrder.title')} style={{ marginTop: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <Button
                    type="primary"
                    icon={<ShoppingCartOutlined />}
                    disabled={!pushData || pushData.triggered || pushData.periodEnded}
                    onClick={() => handleOpenManualOrderModal('UP')}
                    loading={ordering}
                    block
                    style={{ backgroundColor: '#1890ff', borderColor: '#1890ff' }}
                  >
                    {t('cryptoTailMonitor.manualOrder.buttonUp')}
                  </Button>
                </Col>
                <Col span={12}>
                  <Button
                    type="primary"
                    icon={<ShoppingCartOutlined />}
                    disabled={!pushData || pushData.triggered || pushData.periodEnded}
                    onClick={() => handleOpenManualOrderModal('DOWN')}
                    loading={ordering}
                    block
                    style={{ backgroundColor: '#fa8c16', borderColor: '#fa8c16' }}
                  >
                    {t('cryptoTailMonitor.manualOrder.buttonDown')}
                  </Button>
                </Col>
              </Row>
            </Card>
          ) : null}

          {/* 策略信息 */}
          <Card title={t('cryptoTailMonitor.strategyInfo.title')} style={{ marginTop: 16 }}>
            <Row gutter={[16, isMobile ? 12 : 8]}>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.market')}: </Text>
                <Text>{pushData?.marketTitle ?? initData.marketTitle}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.interval')}: </Text>
                <Text>{initData.intervalSeconds === 300 ? '5m' : '15m'}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.account')}: </Text>
                <Text>{initData.accountName || `#${initData.accountId}`}</Text>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.spreadMode')}: </Text>
                <Text>{initData.minSpreadMode}</Text>
                {initData.minSpreadMode === 'FIXED' && initData.minSpreadValue && (
                  <Text> ({formatNumber(initData.minSpreadValue, 4)})</Text>
                )}
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Text type="secondary">{t('cryptoTailMonitor.strategyInfo.spreadDirection')}: </Text>
                <Text>{(initData.spreadDirection ?? 'MIN') === 'MAX' ? t('cryptoTailMonitor.stat.configuredSpreadMax') : t('cryptoTailMonitor.stat.configuredSpreadMin')}</Text>
              </Col>
            </Row>
          </Card>
        </>
      )}

      {/* 手动下单确认弹窗 */}
      <Modal
        title={t('cryptoTailMonitor.manualOrder.confirmTitle')}
        open={manualOrderModal.visible}
        onCancel={handleCloseManualOrderModal}
        footer={[
          <Button key="cancel" onClick={handleCloseManualOrderModal}>
            {t('cryptoTailMonitor.manualOrder.cancel')}
          </Button>,
          <Button
            key="confirm"
            type="primary"
            onClick={handleManualOrder}
            loading={ordering}
            style={
              manualOrderModal.direction === 'UP'
                ? { backgroundColor: '#1890ff', borderColor: '#1890ff' }
                : { backgroundColor: '#fa8c16', borderColor: '#fa8c16' }
            }
          >
            {t('cryptoTailMonitor.manualOrder.confirm')}
          </Button>
        ]}
        width={isMobile ? '100%' : 480}
        style={isMobile ? { maxWidth: '100%', top: 0, paddingBottom: 0 } : undefined}
      >
        {initData && (
          <Space direction="vertical" style={{ width: '100%' }} size={16}>
            <Row gutter={[12, 8]}>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.marketTitle')}
                </Text>
                <Text>{pushData?.marketTitle ?? initData.marketTitle}</Text>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.direction')}
                </Text>
                <Text
                  strong
                  style={{
                    color: manualOrderModal.direction === 'UP' ? '#1890ff' : '#fa8c16'
                  }}
                >
                  {manualOrderModal.direction === 'UP'
                    ? t('cryptoTailMonitor.manualOrder.directionUp')
                    : t('cryptoTailMonitor.manualOrder.directionDown')}
                </Text>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.availableBalance')}
                </Text>
                <Text strong style={{ fontSize: 16, color: '#52c41a' }}>
                  {manualOrderModal.availableBalance ? formatNumber(manualOrderModal.availableBalance, 2) : '-'} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                </Text>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.orderPrice')} ({t('cryptoTailMonitor.manualOrder.orderUnit')})
                </Text>
                <Space.Compact style={{ width: '100%' }}>
                  <InputNumber
                    style={{ width: '100%' }}
                    value={manualOrderModal.price ? parseFloat(manualOrderModal.price) : undefined}
                    onChange={handlePriceChange}
                    min={0}
                    max={1}
                    step={0.0001}
                    precision={4}
                    placeholder="0.0000"
                  />
                  <Button onClick={handleFetchLatestPrice} icon={<SyncOutlined />}>
                    {t('cryptoTailMonitor.manualOrder.fetchLatestPrice')}
                  </Button>
                </Space.Compact>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.orderSize')} ({t('cryptoTailMonitor.manualOrder.sizeUnit')})
                </Text>
                <Space.Compact style={{ width: '100%' }}>
                  <InputNumber
                    style={{ width: '100%' }}
                    value={manualOrderModal.size ? parseFloat(manualOrderModal.size) : undefined}
                    onChange={handleSizeChange}
                    min={1}
                    precision={2}
                    placeholder="1"
                  />
                  <Button onClick={handleMaxSize} type="primary" ghost>
                    {t('cryptoTailMonitor.manualOrder.maxSize')}
                  </Button>
                </Space.Compact>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.totalAmount')}
                </Text>
                <Text strong style={{ fontSize: 16 }}>
                  {manualOrderModal.totalAmount} {t('cryptoTailMonitor.manualOrder.orderUnit')}
                </Text>
              </Col>
              <Col span={24}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>
                  {t('cryptoTailMonitor.manualOrder.account')}
                </Text>
                <Text>{initData.accountName || `#${initData.accountId}`}</Text>
              </Col>
            </Row>
          </Space>
        )}
      </Modal>

      {/* 移动端底部悬浮按钮 */}
      {isMobile && (
        <div
          style={{
            position: 'fixed',
            bottom: 0,
            left: 0,
            right: 0,
            zIndex: 1000,
            padding: '12px 16px',
            background: 'rgba(255, 255, 255, 0.95)',
            backdropFilter: 'blur(10px)',
            borderTop: '1px solid #f0f0f0',
            boxShadow: '0 -2px 8px rgba(0, 0, 0, 0.06)'
          }}
        >
          <div style={{ display: 'flex', width: '100%', gap: 0 }}>
            <Button
              type="primary"
              icon={<ShoppingCartOutlined />}
              disabled={!pushData || pushData.triggered || pushData.periodEnded}
              onClick={() => handleOpenManualOrderModal('UP')}
              loading={ordering}
              style={{ flex: 1, backgroundColor: '#1890ff', borderColor: '#1890ff', height: 44, borderRadius: '6px 0 0 6px' }}
            >
              {t('cryptoTailMonitor.manualOrder.buttonUp')}
            </Button>
            <Button
              type="primary"
              icon={<ShoppingCartOutlined />}
              disabled={!pushData || pushData.triggered || pushData.periodEnded}
              onClick={() => handleOpenManualOrderModal('DOWN')}
              loading={ordering}
              style={{ flex: 1, backgroundColor: '#fa8c16', borderColor: '#fa8c16', height: 44, borderRadius: '0 6px 6px 0' }}
            >
              {t('cryptoTailMonitor.manualOrder.buttonDown')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

export default CryptoTailMonitor
