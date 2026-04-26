import { useEffect, useState } from 'react'
import { Modal, Row, Col, Statistic, Spin, message } from 'antd'
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
              {t('copyTradingOrders.currentPositionCost') || '当前持仓成本'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.currentPositionCost)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.currentPositionValue') || '当前持仓市值'}
            </div>
            <div style={{ fontSize: '16px', fontWeight: '500', color: '#333', flex: '1', textAlign: 'right' }}>
              <span style={{ fontSize: 'clamp(12px, 4vw, 16px)' }}>{formatUSDC(statistics.currentPositionValue)} USDC</span>
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ fontSize: '14px', color: '#666', flex: '0 0 auto', marginRight: '12px' }}>
              {t('copyTradingOrders.totalPnl') || '总盈亏（含未实现）'}
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
                title={t('copyTradingOrders.currentPositionCost') || '当前持仓成本'}
                value={formatUSDC(statistics.currentPositionCost)}
                suffix="USDC"
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.currentPositionValue') || '当前持仓市值'}
                value={formatUSDC(statistics.currentPositionValue)}
                suffix="USDC"
              />
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Statistic
                title={t('copyTradingOrders.totalPnl') || '总盈亏（含未实现）'}
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
          </Row>
        </div>
      )}
    </Modal>
  )
}

export default StatisticsModal

