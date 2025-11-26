import { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, message } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { Statistics as StatisticsType } from '../types'

const Statistics: React.FC = () => {
  const [stats, setStats] = useState<StatisticsType | null>(null)
  const [loading, setLoading] = useState(false)
  
  useEffect(() => {
    fetchStatistics()
  }, [])
  
  const fetchStatistics = async () => {
    setLoading(true)
    try {
      const response = await apiService.statistics.global()
      if (response.data.code === 0 && response.data.data) {
        setStats(response.data.data)
      } else {
        message.error(response.data.msg || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取统计信息失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <h2>统计信息</h2>
      </div>
      
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="总订单数"
              value={stats?.totalOrders || 0}
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="总盈亏"
              value={stats?.totalPnl || '0'}
              precision={2}
              prefix={stats?.totalPnl && parseFloat(stats.totalPnl) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
              valueStyle={{ color: stats?.totalPnl && parseFloat(stats.totalPnl || '0') >= 0 ? '#3f8600' : '#cf1322' }}
              suffix="USDC"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="胜率"
              value={stats?.winRate || '0'}
              precision={2}
              suffix="%"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="平均盈亏"
              value={stats?.avgPnl || '0'}
              precision={2}
              suffix="USDC"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="最大盈利"
              value={stats?.maxProfit || '0'}
              precision={2}
              prefix={<ArrowUpOutlined />}
              valueStyle={{ color: '#3f8600' }}
              suffix="USDC"
              loading={loading}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={8}>
          <Card>
            <Statistic
              title="最大亏损"
              value={stats?.maxLoss || '0'}
              precision={2}
              prefix={<ArrowDownOutlined />}
              valueStyle={{ color: '#cf1322' }}
              suffix="USDC"
              loading={loading}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}

export default Statistics

