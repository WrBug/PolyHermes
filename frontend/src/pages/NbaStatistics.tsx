import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Card, Statistic, Row, Col, Table, DatePicker, Select, Space, message } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'
import dayjs from 'dayjs'

const { RangePicker } = DatePicker
const { Option } = Select

interface StrategyStatistics {
  totalSignals: number
  buySignals: number
  sellSignals: number
  successSignals: number
  failedSignals: number
  totalProfit: string
  totalVolume: string
  successRate: number
  averageProfit: string
}

const NbaStatistics: React.FC = () => {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [statistics, setStatistics] = useState<StrategyStatistics | null>(null)
  const [loading, setLoading] = useState(false)
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)
  const [timeDimension, setTimeDimension] = useState<'today' | 'week' | 'month' | 'all'>('all')
  
  useEffect(() => {
    if (id) {
      fetchStatistics()
    }
  }, [id, dateRange, timeDimension])
  
  const fetchStatistics = async () => {
    if (!id) return
    
    setLoading(true)
    try {
      let startDate: string | undefined
      let endDate: string | undefined
      
      if (dateRange) {
        startDate = dateRange[0].format('YYYY-MM-DD')
        endDate = dateRange[1].format('YYYY-MM-DD')
      } else {
        // 根据时间维度设置日期范围
        const now = dayjs()
        switch (timeDimension) {
          case 'today':
            startDate = now.format('YYYY-MM-DD')
            endDate = now.format('YYYY-MM-DD')
            break
          case 'week':
            startDate = now.subtract(7, 'day').format('YYYY-MM-DD')
            endDate = now.format('YYYY-MM-DD')
            break
          case 'month':
            startDate = now.subtract(30, 'day').format('YYYY-MM-DD')
            endDate = now.format('YYYY-MM-DD')
            break
          case 'all':
            // 不设置日期范围
            break
        }
      }
      
      const response = await apiService.nbaStatistics.strategy({
        strategyId: parseInt(id),
        startDate,
        endDate
      })
      
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data
        const successRate = data.totalSignals > 0 
          ? (data.successSignals / data.totalSignals * 100) 
          : 0
        const averageProfit = data.totalSignals > 0
          ? (parseFloat(data.totalProfit) / data.totalSignals).toString()
          : '0'
        
        setStatistics({
          ...data,
          successRate,
          averageProfit
        })
      } else {
        message.error(response.data.msg || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取统计信息失败')
    } finally {
      setLoading(false)
    }
  }
  
  const getProfitColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const getProfitIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }
  
  return (
    <div style={{ padding: isMobile ? '16px' : '24px' }}>
      <Card>
        <div style={{ marginBottom: 24 }}>
          <Space>
            <Select
              value={timeDimension}
              onChange={setTimeDimension}
              style={{ width: 150 }}
            >
              <Option value="today">今日</Option>
              <Option value="week">本周</Option>
              <Option value="month">本月</Option>
              <Option value="all">全部</Option>
            </Select>
            <RangePicker
              value={dateRange}
              onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
              allowClear
            />
          </Space>
        </div>
        
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="信号总数"
                value={statistics?.totalSignals || 0}
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="买入信号"
                value={statistics?.buySignals || 0}
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="卖出信号"
                value={statistics?.sellSignals || 0}
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="成功率"
                value={statistics?.successRate || 0}
                precision={2}
                suffix="%"
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="总盈亏"
                value={statistics?.totalProfit || '0'}
                precision={4}
                suffix="USDC"
                valueStyle={{ color: getProfitColor(statistics?.totalProfit || '0') }}
                prefix={getProfitIcon(statistics?.totalProfit || '0')}
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="平均盈亏"
                value={statistics?.averageProfit || '0'}
                precision={4}
                suffix="USDC"
                valueStyle={{ color: getProfitColor(statistics?.averageProfit || '0') }}
                prefix={getProfitIcon(statistics?.averageProfit || '0')}
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="总交易量"
                value={statistics?.totalVolume || '0'}
                precision={4}
                suffix="USDC"
                loading={loading}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title="成功信号"
                value={statistics?.successSignals || 0}
                loading={loading}
              />
            </Card>
          </Col>
        </Row>
      </Card>
    </div>
  )
}

export default NbaStatistics

