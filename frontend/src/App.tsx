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
import OrderList from './pages/OrderList'
import Statistics from './pages/Statistics'

function App() {
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
            <Route path="/orders" element={<OrderList />} />
            <Route path="/statistics" element={<Statistics />} />
          </Routes>
        </Layout>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App

