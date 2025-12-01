import { useEffect, useCallback } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ConfigProvider, notification } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Layout from './components/Layout'
import AccountList from './pages/AccountList'
import AccountImport from './pages/AccountImport'
import AccountDetail from './pages/AccountDetail'
import AccountEdit from './pages/AccountEdit'
import LeaderList from './pages/LeaderList'
import LeaderAdd from './pages/LeaderAdd'
import LeaderEdit from './pages/LeaderEdit'
import ConfigPage from './pages/ConfigPage'
import PositionList from './pages/PositionList'
import Statistics from './pages/Statistics'
import TemplateList from './pages/TemplateList'
import TemplateAdd from './pages/TemplateAdd'
import TemplateEdit from './pages/TemplateEdit'
import CopyTradingList from './pages/CopyTradingList'
import CopyTradingAdd from './pages/CopyTradingAdd'
import { wsManager } from './services/websocket'
import type { OrderPushMessage } from './types'

function App() {
  /**
   * 获取订单类型文本
   */
  const getOrderTypeText = useCallback((type: string): string => {
    switch (type) {
      case 'PLACEMENT':
        return '订单创建'
      case 'UPDATE':
        return '订单更新'
      case 'CANCELLATION':
        return '订单取消'
      default:
        return '订单事件'
    }
  }, [])
  
  /**
   * 处理订单推送消息，显示全局通知
   */
  const handleOrderPush = useCallback((message: OrderPushMessage) => {
    const { accountName, order, orderDetail } = message
    
    // 根据订单类型和操作类型确定通知内容
    const orderTypeText = getOrderTypeText(order.type)
    const sideText = order.side === 'BUY' ? '买入' : '卖出'
    
    // 如果有市场名称，在标题中显示
    const marketName = orderDetail?.marketName || order.market.substring(0, 8) + '...'
    const title = `${accountName} - ${orderTypeText}`
    
    // 优先使用订单详情中的数据，如果没有则使用 WebSocket 消息中的数据
    const price = orderDetail ? parseFloat(orderDetail.price).toFixed(4) : parseFloat(order.price).toFixed(4)
    const size = orderDetail ? parseFloat(orderDetail.size).toFixed(2) : parseFloat(order.original_size).toFixed(2)
    const filled = orderDetail ? parseFloat(orderDetail.filled).toFixed(2) : parseFloat(order.size_matched).toFixed(2)
    const status = orderDetail?.status || 'UNKNOWN'
    
    // 构建描述信息
    let description = `市场: ${marketName}\n${sideText} ${size} @ ${price}`
    
    // 如果有订单详情，显示更详细的信息
    if (orderDetail) {
      description += `\n状态: ${status}`
      if (parseFloat(filled) > 0) {
        description += ` | 已成交: ${filled}`
      }
      const remaining = (parseFloat(size) - parseFloat(filled)).toFixed(2)
      if (parseFloat(remaining) > 0) {
        description += ` | 剩余: ${remaining}`
      }
    } else if (order.type === 'UPDATE' && parseFloat(order.size_matched) > 0) {
      // 如果没有订单详情，使用 WebSocket 消息中的已成交数量
      description += `\n已成交: ${filled}`
    }
    
    // 根据订单类型选择通知类型
    let notificationType: 'info' | 'success' | 'warning' | 'error' = 'info'
    if (order.type === 'PLACEMENT') {
      notificationType = 'info'
    } else if (order.type === 'UPDATE') {
      notificationType = 'success'
    } else if (order.type === 'CANCELLATION') {
      notificationType = 'warning'
    }
    
    // 显示通知
    notification[notificationType]({
      message: title,
      description: description,
      placement: 'topRight',
      duration: order.type === 'CANCELLATION' ? 3 : 5,  // 取消订单通知显示时间短一些
      key: `order-${order.id}`,  // 使用订单 ID 作为 key，避免重复通知
    })
  }, [getOrderTypeText])
  
  // 应用启动时立即建立全局 WebSocket 连接
  useEffect(() => {
    // 立即建立连接（如果还未连接）
    if (!wsManager.isConnected()) {
      wsManager.connect()
    }
    
    // 注意：应用不会卸载，所以不需要在 cleanup 中断开连接
    // WebSocket 连接会在整个应用生命周期中保持，并自动重连
  }, [])
  
  // 订阅订单推送并显示全局通知
  useEffect(() => {
    const unsubscribe = wsManager.subscribe('order', (data: OrderPushMessage) => {
      handleOrderPush(data)
    })
    
    return () => {
      unsubscribe()
    }
  }, [handleOrderPush])
  
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <Layout>
          <Routes>
            <Route path="/" element={<AccountList />} />
            <Route path="/accounts" element={<AccountList />} />
            <Route path="/accounts/import" element={<AccountImport />} />
            <Route path="/accounts/detail" element={<AccountDetail />} />
            <Route path="/accounts/edit" element={<AccountEdit />} />
            <Route path="/leaders" element={<LeaderList />} />
            <Route path="/leaders/add" element={<LeaderAdd />} />
            <Route path="/leaders/edit" element={<LeaderEdit />} />
            <Route path="/templates" element={<TemplateList />} />
            <Route path="/templates/add" element={<TemplateAdd />} />
            <Route path="/templates/edit/:id" element={<TemplateEdit />} />
            <Route path="/copy-trading" element={<CopyTradingList />} />
            <Route path="/copy-trading/add" element={<CopyTradingAdd />} />
            <Route path="/config" element={<ConfigPage />} />
            <Route path="/positions" element={<PositionList />} />
            <Route path="/statistics" element={<Statistics />} />
          </Routes>
        </Layout>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App

