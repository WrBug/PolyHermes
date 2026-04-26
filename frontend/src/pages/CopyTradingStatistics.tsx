import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Row, Col, Statistic, Tag, Button, message, Spin } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined, LeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { formatUSDC, formatNumber } from '../utils'
import { useMediaQuery } from 'react-responsive'
import type { CopyTradingStatistics } from '../types'

const CopyTradingStatisticsPage: React.FC = () => {
  const { copyTradingId } = useParams<{ copyTradingId: string }>()
  const navigate = useNavigate()
  useMediaQuery({ maxWidth: 768 }) // 用于响应式布局，但当前页面未使用
  const [loading, setLoading] = useState(false)
  const [statistics, setStatistics] = useState<CopyTradingStatistics | null>(null)

  useEffect(() => {
    if (copyTradingId) {
      fetchStatistics()
    }
  }, [copyTradingId])

  const fetchStatistics = async () => {
    if (!copyTradingId) return

    setLoading(true)
    try {
      const response = await apiService.statistics.detail({ copyTradingId: parseInt(copyTradingId) })
      if (response.data.code === 0 && response.data.data) {
        setStatistics(response.data.data)
      } else {
        message.error(response.data.msg || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取统计信息失败')
    } finally {
      setLoading(false)
    }
  }

  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }

  const getPnlIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }

  const formatPercent = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '-'
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (!statistics) {
    return (
      <Card>
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>暂无统计数据</p>
          <Button onClick={() => navigate('/copy-trading')}>返回列表</Button>
        </div>
      </Card>
    )
  }

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button icon={<LeftOutlined />} onClick={() => navigate('/copy-trading')}>
              返回
            </Button>
            <h2 style={{ margin: 0 }}>跟单关系统计</h2>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button onClick={() => navigate(`/copy-trading/orders/buy/${copyTradingId}`)}>
              买入订单
            </Button>
            <Button onClick={() => navigate(`/copy-trading/orders/sell/${copyTradingId}`)}>
              卖出订单
            </Button>
            <Button onClick={() => navigate(`/copy-trading/orders/matched/${copyTradingId}`)}>
              匹配关系
            </Button>
          </div>
        </div>
      </Card>

      {/* 基本信息卡片 */}
      <Card title="基本信息" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>账户名称</div>
              <div style={{ fontSize: 16, fontWeight: 500 }}>
                {statistics.accountName || `账户 ${statistics.accountId}`}
              </div>
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>Leader 名称</div>
              <div style={{ fontSize: 16, fontWeight: 500 }}>
                {statistics.leaderName || `Leader ${statistics.leaderId}`}
              </div>
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div>
              <div style={{ color: '#999', fontSize: 14, marginBottom: 4 }}>跟单状态</div>
              <div>
                <Tag color={statistics.enabled ? 'green' : 'red'}>
                  {statistics.enabled ? '启用' : '禁用'}
                </Tag>
              </div>
            </div>
          </Col>
        </Row>
      </Card>

      {/* 买入统计卡片 */}
      <Card title="买入统计" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总买入数量"
              value={formatNumber(statistics.totalBuyQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总买入金额"
              value={formatUSDC(statistics.totalBuyAmount)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总买入订单数"
              value={formatNumber(statistics.totalBuyOrders)}
              suffix="笔"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="平均买入价格"
              value={formatNumber(statistics.avgBuyPrice, 4)}
              suffix=""
            />
          </Col>
        </Row>
      </Card>

      {/* 卖出统计卡片 */}
      <Card title="卖出统计" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title="总卖出数量"
              value={formatNumber(statistics.totalSellQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title="总卖出金额"
              value={formatUSDC(statistics.totalSellAmount)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={8}>
            <Statistic
              title="总卖出订单数"
              value={formatNumber(statistics.totalSellOrders)}
              suffix="笔"
            />
          </Col>
        </Row>
      </Card>

      {/* 持仓统计卡片 */}
      <Card title="持仓统计" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="当前持仓数量"
              value={formatNumber(statistics.currentPositionQuantity, 4)}
              suffix=""
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="当前持仓成本"
              value={formatUSDC(statistics.currentPositionCost)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="当前持仓市值"
              value={formatUSDC(statistics.currentPositionValue)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="平均买入价格"
              value={formatNumber(statistics.avgBuyPrice, 4)}
              suffix=""
            />
          </Col>
        </Row>
      </Card>

      {/* 盈亏统计卡片 */}
      <Card title="盈亏统计">
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总已实现盈亏"
              value={formatUSDC(statistics.totalRealizedPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalRealizedPnl) }}
              prefix={getPnlIcon(statistics.totalRealizedPnl)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总未实现盈亏"
              value={formatUSDC(statistics.totalUnrealizedPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalUnrealizedPnl) }}
              prefix={getPnlIcon(statistics.totalUnrealizedPnl)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总盈亏（含未实现）"
              value={formatUSDC(statistics.totalPnl)}
              valueStyle={{ color: getPnlColor(statistics.totalPnl) }}
              prefix={getPnlIcon(statistics.totalPnl)}
              suffix="USDC"
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Statistic
              title="总盈亏百分比"
              value={formatPercent(statistics.totalPnlPercent)}
              valueStyle={{ color: getPnlColor(statistics.totalPnlPercent) }}
              prefix={getPnlIcon(statistics.totalPnlPercent)}
            />
          </Col>
        </Row>
      </Card>
    </div>
  )
}

export default CopyTradingStatisticsPage

