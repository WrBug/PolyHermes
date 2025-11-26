import { useEffect } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import Layout from './components/Layout'
import AccountList from './pages/AccountList'
import AccountImport from './pages/AccountImport'
import AccountDetail from './pages/AccountDetail'
import AccountEdit from './pages/AccountEdit'
import LeaderList from './pages/LeaderList'
import LeaderAdd from './pages/LeaderAdd'
import ConfigPage from './pages/ConfigPage'
import PositionList from './pages/PositionList'
import Statistics from './pages/Statistics'
import { wsManager } from './services/websocket'

function App() {
  // 应用启动时立即建立全局 WebSocket 连接
  useEffect(() => {
    // 立即建立连接（如果还未连接）
    if (!wsManager.isConnected()) {
      wsManager.connect()
    }
    
    // 注意：应用不会卸载，所以不需要在 cleanup 中断开连接
    // WebSocket 连接会在整个应用生命周期中保持，并自动重连
  }, [])
  
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

