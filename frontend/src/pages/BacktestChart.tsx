import { useEffect, useRef } from 'react'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { useTranslation } from 'react-i18next'

interface BacktestChartProps {
  trades: {
    tradeTime: number
    balanceAfter: string
  }[]
}

const BacktestChart: React.FC<BacktestChartProps> = ({ trades }) => {
  const { t } = useTranslation()
  const chartRef = useRef<HTMLDivElement>(null)
  const chartInstance = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!chartRef.current) return

    // 初始化图表
    chartInstance.current = echarts.init(chartRef.current)

    // 监听窗口大小变化
    const handleResize = () => {
      chartInstance.current?.resize()
    }
    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
      chartInstance.current?.dispose()
    }
  }, [])

  useEffect(() => {
    if (!chartInstance.current || trades.length === 0) return

    // 准备数据
    const data = trades.map((trade) => ({
      time: new Date(trade.tradeTime).toLocaleString(),
      value: parseFloat(trade.balanceAfter)
    }))

    // 初始余额（第一笔交易前的余额）
    const initialBalance = data[0]?.value || 0

    // 数据压缩：如果数据点太多，进行采样
    const maxPoints = 500 // 最多显示500个点
    let compressedData = data
    if (data.length > maxPoints) {
      const step = Math.ceil(data.length / maxPoints)
      compressedData = data.filter((_, index) => index % step === 0)
      // 确保最后一个点被包含
      if (compressedData[compressedData.length - 1] !== data[data.length - 1]) {
        compressedData.push(data[data.length - 1])
      }
    }

    const times = compressedData.map(item => item.time)
    const values = compressedData.map(item => item.value)

    const option: EChartsOption = {
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const param = params[0]
          const value = parseFloat(param.value).toFixed(2)
          const diffValue = param.value - initialBalance
          const diff = diffValue.toFixed(2)
          const diffPercent = (diffValue / initialBalance * 100).toFixed(2)
          const color = diffValue >= 0 ? '#52c41a' : '#ff4d4f'
          return `
            <div>
              <div>${t('backtest.tradeTime')}: ${param.name}</div>
              <div>${t('backtest.balanceAfter')}: ${value} USDC</div>
              <div style="color: ${color}">
                ${t('backtest.profitLoss')}: ${diff} USDC (${diffPercent}%)
              </div>
            </div>
          `
        }
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '8%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: times,
        axisLabel: {
          rotate: 45,
          formatter: (value: string) => {
            // 简化时间显示，只显示 HH:mm
            const parts = value.split(' ')
            if (parts.length > 1) {
              const timeParts = parts[1].split(':')
              if (timeParts.length >= 2) {
                return `${timeParts[0]}:${timeParts[1]}`
              }
            }
            return value
          }
        },
        axisLine: {
          lineStyle: {
            color: '#e0e0e0'
          }
        },
        axisTick: {
          alignWithLabel: true,
          lineStyle: {
            color: '#e0e0e0'
          }
        }
      },
      yAxis: {
        type: 'value',
        name: 'USDC',
        nameLocation: 'end',
        nameGap: 10,
        axisLabel: {
          formatter: (value: number) => value.toFixed(2)
        },
        splitLine: {
          lineStyle: {
            color: '#f0f0f0'
          }
        },
        axisLine: {
          lineStyle: {
            color: '#e0e0e0'
          }
        }
      },
      series: [
        {
          name: t('backtest.balanceAfter'),
          type: 'line',
          data: values,
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          lineStyle: {
            width: 2,
            color: '#1890ff'
          },
          itemStyle: {
            color: '#1890ff'
          },
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
          markLine: {
            data: [
              {
                name: t('backtest.initialBalance'),
                yAxis: initialBalance,
                label: {
                  formatter: `${t('backtest.initialBalance')}: ${initialBalance.toFixed(2)}`
                },
                lineStyle: {
                  type: 'dashed',
                  color: '#999',
                  width: 1
                }
              }
            ]
          }
        }
      ],
      dataZoom: [
        {
          type: 'inside',
          start: 0,
          end: 100
        },
        {
          type: 'slider',
          start: 0,
          end: 100,
          height: 20,
          bottom: 20
        }
      ]
    }

    chartInstance.current.setOption(option)
  }, [trades, t])

  return (
    <div
      ref={chartRef}
      style={{
        width: '100%',
        height: 400
      }}
    />
  )
}

export default BacktestChart

