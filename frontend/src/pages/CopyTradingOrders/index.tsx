import { useState, useEffect } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { Modal, Tabs } from 'antd'
import { useTranslation } from 'react-i18next'
import BuyOrdersTab from './BuyOrdersTab'
import SellOrdersTab from './SellOrdersTab'
import MatchedOrdersTab from './MatchedOrdersTab'

type TabType = 'buy' | 'sell' | 'matched'

interface CopyTradingOrdersModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
  defaultTab?: TabType
}

const CopyTradingOrdersModal: React.FC<CopyTradingOrdersModalProps> = ({
  open,
  onClose,
  copyTradingId,
  defaultTab = 'buy'
}) => {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<TabType>(defaultTab)
  
  useEffect(() => {
    if (open) {
      setActiveTab(defaultTab)
    }
  }, [open, defaultTab])
  
  return (
    <Modal
      title={t('copyTradingOrders.title') || '订单列表'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
    >
      <Tabs 
        activeKey={activeTab} 
        onChange={(key) => setActiveTab(key as TabType)}
        items={[
          {
            key: 'buy',
            label: t('copyTradingOrders.buyOrders') || '买入订单',
            children: <BuyOrdersTab copyTradingId={copyTradingId} />
          },
          {
            key: 'sell',
            label: t('copyTradingOrders.sellOrders') || '卖出订单',
            children: <SellOrdersTab copyTradingId={copyTradingId} />
          },
          {
            key: 'matched',
            label: t('copyTradingOrders.matchedOrders') || '匹配关系',
            children: <MatchedOrdersTab copyTradingId={copyTradingId} />
          }
        ]}
      />
    </Modal>
  )
}

export default CopyTradingOrdersModal

