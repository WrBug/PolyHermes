import { useEffect, useState } from 'react'
import { Modal, Row, Col, Statistic, Spin, message, Card } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import { formatUSDC } from '../../utils'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import type { CopyTradingStatistics } from '../../types'

interface StatisticsModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
}

const StatisticsModal: React.FC<StatisticsModalProps> = ({
  open,
  onClose,
  copyTradingId
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [statistics, setStatistics] = useState<CopyTradingStatistics | null>(null)
  
  useEffect(() => {
    if (open && copyTradingId) {
      fetchStatistics()
    }
  }, [open, copyTradingId])
  
  const fetchStatistics = async () => {
    if (!copyTradingId) return
    
    setLoading(true)
    try {
      const response = await apiService.statistics.detail({ copyTradingId: parseInt(copyTradingId) })
      if (response.data.code === 0 && response.data.data) {
        setStatistics(response.data.data)
      } else {
        message.error(response.data.msg || t('copyTradingOrders.fetchStatisticsFailed') || '获取统计信息失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingOrders.fetchStatisticsFailed') || '获取统计信息失败')
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
  
  return (
    <Modal
      title={t('copyTradingOrders.statistics') || '跟单关系统计'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <Spin size="large" />
        </div>
      ) : !statistics ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>{t('copyTradingOrders.noStatistics') || '暂无统计数据'}</p>
        </div>
      ) : isMobile ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalBuyOrders') || '总买入订单数'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowUpOutlined style={{ color: '#1890ff', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{statistics.totalBuyOrders}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalSellOrders') || '总卖出订单数'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowDownOutlined style={{ color: '#ff4d4f', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{statistics.totalSellOrders}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalMatchedOrders') || '总匹配订单数'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{statistics.totalMatchedOrders || 0}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalBuyAmount') || '总买入金额'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowUpOutlined style={{ color: '#1890ff', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.totalBuyAmount)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalSellAmount') || '总卖出金额'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              <ArrowDownOutlined style={{ color: '#ff4d4f', fontSize: '14px' }} />
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.totalSellAmount)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalPnl') || '总盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: 'bold', color: getPnlColor(statistics.totalPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.totalPnl)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalRealizedPnl') || '总已实现盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.totalRealizedPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalRealizedPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.totalRealizedPnl)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalUnrealizedPnl') || '总未实现盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.totalUnrealizedPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.totalUnrealizedPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.totalUnrealizedPnl)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.winRate') || '胜率'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatPercent(statistics.winRate)}</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.averagePnl') || '平均盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.averagePnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.averagePnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.averagePnl)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.maxPnl') || '最大盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.maxPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.maxPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.maxPnl)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.minPnl') || '最小盈亏'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: getPnlColor(statistics.minPnl), flex: '1', textAlign: 'right', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '4px' }}>
              {getPnlIcon(statistics.minPnl)}
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.minPnl)} USDC</span>
            </div>
          </div>
        </div>
      ) : (
        <div>
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalBuyOrders') || '总买入订单数'}
                value={statistics.totalBuyOrders}
                prefix={<ArrowUpOutlined style={{ color: '#1890ff' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalSellOrders') || '总卖出订单数'}
                value={statistics.totalSellOrders}
                prefix={<ArrowDownOutlined style={{ color: '#ff4d4f' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalMatchedOrders') || '总匹配订单数'}
                value={statistics.totalMatchedOrders || 0}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalBuyAmount') || '总买入金额'}
                value={formatUSDC(statistics.totalBuyAmount)}
                suffix="USDC"
                prefix={<ArrowUpOutlined style={{ color: '#1890ff' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalSellAmount') || '总卖出金额'}
                value={formatUSDC(statistics.totalSellAmount)}
                suffix="USDC"
                prefix={<ArrowDownOutlined style={{ color: '#ff4d4f' }} />}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalPnl') || '总盈亏'}
                value={formatUSDC(statistics.totalPnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.totalPnl) }}
                prefix={getPnlIcon(statistics.totalPnl)}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalRealizedPnl') || '总已实现盈亏'}
                value={formatUSDC(statistics.totalRealizedPnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.totalRealizedPnl) }}
                prefix={getPnlIcon(statistics.totalRealizedPnl)}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalUnrealizedPnl') || '总未实现盈亏'}
                value={formatUSDC(statistics.totalUnrealizedPnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.totalUnrealizedPnl) }}
                prefix={getPnlIcon(statistics.totalUnrealizedPnl)}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.winRate') || '胜率'}
                value={formatPercent(statistics.winRate)}
                suffix="%"
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.averagePnl') || '平均盈亏'}
                value={formatUSDC(statistics.averagePnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.averagePnl) }}
                prefix={getPnlIcon(statistics.averagePnl)}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.maxPnl') || '最大盈亏'}
                value={formatUSDC(statistics.maxPnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.maxPnl) }}
                prefix={getPnlIcon(statistics.maxPnl)}
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.minPnl') || '最小盈亏'}
                value={formatUSDC(statistics.minPnl)}
                suffix="USDC"
                valueStyle={{ color: getPnlColor(statistics.minPnl) }}
                prefix={getPnlIcon(statistics.minPnl)}
              />
            </Col>
          </Row>
        </div>
      )}
    </Modal>
  )
}

export default StatisticsModal

