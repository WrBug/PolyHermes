import { useEffect, useCallback, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { ConfigProvider, notification, Spin } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import zhTW from 'antd/locale/zh_TW'
import enUS from 'antd/locale/en_US'
import { useTranslation } from 'react-i18next'
import Layout from './components/Layout'
import Login from './pages/Login'
import ResetPassword from './pages/ResetPassword'
import AccountList from './pages/AccountList'
import UserList from './pages/UserList'
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
import CopyTradingStatistics from './pages/CopyTradingStatistics'
import CopyTradingBuyOrders from './pages/CopyTradingBuyOrders'
import CopyTradingSellOrders from './pages/CopyTradingSellOrders'
import CopyTradingMatchedOrders from './pages/CopyTradingMatchedOrders'
import FilteredOrdersList from './pages/FilteredOrdersList'
import SystemSettings from './pages/SystemSettings'
import ApiHealthStatus from './pages/ApiHealthStatus'
import RpcNodeSettings from './pages/RpcNodeSettings'
import Announcements from './pages/Announcements'
import { wsManager } from './services/websocket'
import type { OrderPushMessage } from './types'
import { apiService } from './services/api'
import { hasToken } from './utils'

/**
 * 路由保护组件
 */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation()
  const isAuthPage = location.pathname === '/login' || location.pathname === '/reset-password'
  
  if (isAuthPage) {
    return <>{children}</>
  }
  
  if (!hasToken()) {
    return <Navigate to="/login" replace />
  }
  
  return <Layout>{children}</Layout>
}

function App() {
  const { t, i18n } = useTranslation()
  const [isFirstUse, setIsFirstUse] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(true)
  
  // 根据当前语言设置 Ant Design 的 locale
  const getAntdLocale = () => {
    const lang = i18n.language || 'en'
    if (lang.startsWith('zh-CN')) return zhCN
    if (lang.startsWith('zh-TW') || lang.startsWith('zh-HK')) return zhTW
    return enUS
  }
  
  /**
   * 获取订单类型文本
   */
  const getOrderTypeText = useCallback((type: string): string => {
    switch (type) {
      case 'PLACEMENT':
        return t('order.create')
      case 'UPDATE':
        return t('order.update')
      case 'CANCELLATION':
        return t('order.cancel')
      default:
        return t('order.event')
    }
  }, [t])
  
  /**
   * 处理订单推送消息，显示全局通知
   */
  const handleOrderPush = useCallback((message: OrderPushMessage) => {
    const { accountName, order, orderDetail, leaderName, configName } = message
    
    // 根据订单类型和操作类型确定通知内容
    const orderTypeText = getOrderTypeText(order.type)
    const sideText = order.side === 'BUY' ? t('order.buy') : t('order.sell')
    
    // 如果有市场名称，在标题中显示
    const marketName = orderDetail?.marketName || order.market.substring(0, 8) + '...'
    
    // 构建标题：如果是跟单订单，显示 leader 备注和跟单配置名
    let title = `${accountName} - ${orderTypeText}`
    if (leaderName || configName) {
      const parts: string[] = []
      if (configName) {
        parts.push(configName)
      }
      if (leaderName) {
        parts.push(`Leader: ${leaderName}`)
      }
      if (parts.length > 0) {
        title = `${accountName} (${parts.join(', ')}) - ${orderTypeText}`
      }
    }
    
    // 优先使用订单详情中的数据，如果没有则使用 WebSocket 消息中的数据
    const price = orderDetail ? parseFloat(orderDetail.price).toFixed(4) : parseFloat(order.price).toFixed(4)
    const size = orderDetail ? parseFloat(orderDetail.size).toFixed(2) : parseFloat(order.original_size).toFixed(2)
    const filled = orderDetail ? parseFloat(orderDetail.filled).toFixed(2) : parseFloat(order.size_matched).toFixed(2)
    const status = orderDetail?.status || 'UNKNOWN'
    
    // 构建描述信息
    let description = `${t('order.market')}: ${marketName}\n${sideText} ${size} @ ${price}`
    
    // 如果有订单详情，显示更详细的信息
    if (orderDetail) {
      description += `\n${t('order.status')}: ${status}`
      if (parseFloat(filled) > 0) {
        description += ` | ${t('order.filled')}: ${filled}`
      }
      const remaining = (parseFloat(size) - parseFloat(filled)).toFixed(2)
      if (parseFloat(remaining) > 0) {
        description += ` | ${t('order.remaining')}: ${remaining}`
      }
    } else if (order.type === 'UPDATE' && parseFloat(order.size_matched) > 0) {
      // 如果没有订单详情，使用 WebSocket 消息中的已成交数量
      description += `\n${t('order.filled')}: ${filled}`
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
  
  // 应用启动时检查是否首次使用
  useEffect(() => {
    const checkFirstUse = async () => {
      try {
        const response = await apiService.auth.checkFirstUse()
        if (response.data.code === 0 && response.data.data) {
          setIsFirstUse(response.data.data.isFirstUse)
        }
      } catch (error) {
        console.error('检查首次使用失败:', error)
        setIsFirstUse(false)
      } finally {
        setChecking(false)
      }
    }
    
    checkFirstUse()
  }, [])
  
  // 应用启动时立即建立全局 WebSocket 连接（仅在已登录时）
  useEffect(() => {
    // 只有在已登录且不是首次使用的情况下才建立WebSocket连接
    if (!checking && isFirstUse === false && hasToken() && !wsManager.isConnected()) {
      wsManager.connect()
    } else if (!hasToken() && wsManager.isConnected()) {
      // 如果未登录但WebSocket已连接，断开连接
      wsManager.disconnect()
    }
  }, [checking, isFirstUse])
  
  // 订阅订单推送并显示全局通知
  useEffect(() => {
    const unsubscribe = wsManager.subscribe('order', (data: OrderPushMessage) => {
      handleOrderPush(data)
    })
    
    return () => {
      unsubscribe()
    }
  }, [handleOrderPush])
  
  // 如果正在检查首次使用，显示加载中
  if (checking) {
    return (
      <ConfigProvider locale={getAntdLocale()}>
        <div style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100vh'
        }}>
          <Spin size="large" />
        </div>
      </ConfigProvider>
    )
  }
  
  // 如果首次使用，直接跳转到重置密码页面
  if (isFirstUse === true) {
    return (
      <ConfigProvider locale={getAntdLocale()}>
        <BrowserRouter>
          <Routes>
            <Route path="/reset-password" element={<ResetPassword />} />
            <Route path="*" element={<Navigate to="/reset-password" replace />} />
          </Routes>
        </BrowserRouter>
      </ConfigProvider>
    )
  }
  
  return (
    <ConfigProvider locale={getAntdLocale()}>
      <BrowserRouter>
        <Routes>
          {/* 公开路由（不需要鉴权） */}
          <Route path="/login" element={<Login />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          
          {/* 受保护的路由 */}
          <Route path="/" element={<ProtectedRoute><Announcements /></ProtectedRoute>} />
          <Route path="/accounts" element={<ProtectedRoute><AccountList /></ProtectedRoute>} />
          <Route path="/accounts/import" element={<ProtectedRoute><AccountImport /></ProtectedRoute>} />
          <Route path="/accounts/detail" element={<ProtectedRoute><AccountDetail /></ProtectedRoute>} />
          <Route path="/accounts/edit" element={<ProtectedRoute><AccountEdit /></ProtectedRoute>} />
          <Route path="/leaders" element={<ProtectedRoute><LeaderList /></ProtectedRoute>} />
          <Route path="/leaders/add" element={<ProtectedRoute><LeaderAdd /></ProtectedRoute>} />
          <Route path="/leaders/edit" element={<ProtectedRoute><LeaderEdit /></ProtectedRoute>} />
          <Route path="/templates" element={<ProtectedRoute><TemplateList /></ProtectedRoute>} />
          <Route path="/templates/add" element={<ProtectedRoute><TemplateAdd /></ProtectedRoute>} />
          <Route path="/templates/edit/:id" element={<ProtectedRoute><TemplateEdit /></ProtectedRoute>} />
          <Route path="/copy-trading" element={<ProtectedRoute><CopyTradingList /></ProtectedRoute>} />
          <Route path="/copy-trading/statistics/:copyTradingId" element={<ProtectedRoute><CopyTradingStatistics /></ProtectedRoute>} />
          {/* 保留旧路由以保持向后兼容 */}
          <Route path="/copy-trading/orders/buy/:copyTradingId" element={<ProtectedRoute><CopyTradingBuyOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/orders/sell/:copyTradingId" element={<ProtectedRoute><CopyTradingSellOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/orders/matched/:copyTradingId" element={<ProtectedRoute><CopyTradingMatchedOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/filtered-orders/:id" element={<ProtectedRoute><FilteredOrdersList /></ProtectedRoute>} />
          <Route path="/config" element={<ProtectedRoute><ConfigPage /></ProtectedRoute>} />
          <Route path="/positions" element={<ProtectedRoute><PositionList /></ProtectedRoute>} />
          <Route path="/statistics" element={<ProtectedRoute><Statistics /></ProtectedRoute>} />
          <Route path="/users" element={<ProtectedRoute><UserList /></ProtectedRoute>} />
          <Route path="/announcements" element={<ProtectedRoute><Announcements /></ProtectedRoute>} />
          <Route path="/system-settings" element={<ProtectedRoute><SystemSettings /></ProtectedRoute>} />
          <Route path="/system-settings/rpc-nodes" element={<ProtectedRoute><RpcNodeSettings /></ProtectedRoute>} />          <Route path="/system-settings/api-health" element={<ProtectedRoute><ApiHealthStatus /></ProtectedRoute>} />
          
          {/* 默认重定向到登录页 */}
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App

