import { useState, useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Layout as AntLayout, Menu, Drawer, Button, Modal } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import {
  WalletOutlined,
  UserOutlined,
  UnorderedListOutlined,
  BarChartOutlined,
  MenuOutlined,
  FileTextOutlined,
  LinkOutlined,
  AppstoreOutlined,
  TeamOutlined,
  LogoutOutlined,
  SettingOutlined,
  GithubOutlined,
  TwitterOutlined,
  CheckCircleOutlined,
  SendOutlined,
  ApiOutlined,  NotificationOutlined
} from '@ant-design/icons'
import type { MenuProps } from 'antd'
import type { ReactNode } from 'react'
import { removeToken, getVersionText, getGitHubTagUrl } from '../utils'
import { wsManager } from '../services/websocket'
import Logo from './Logo'

const { Header, Content, Sider } = AntLayout

interface LayoutProps {
  children: ReactNode
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  
  // 获取当前选中的菜单项
  const getSelectedKeys = (): string[] => {
    return [location.pathname]
  }
  
  // 获取当前应该打开的父菜单
  const getInitialOpenKeys = (): string[] => {
    const path = location.pathname
    const keys: string[] = []
    if (path.startsWith('/leaders') || path.startsWith('/templates') || path.startsWith('/copy-trading')) {
      keys.push('/copy-trading-management')
    }
    if (path.startsWith('/system-settings')) {
      keys.push('/system-settings')
    }
    return keys
  }
  
  const [openKeys, setOpenKeys] = useState<string[]>(getInitialOpenKeys())
  
  // 当路径变化时，自动打开对应的父菜单
  useEffect(() => {
    const path = location.pathname
    const keys: string[] = []
    if (path.startsWith('/leaders') || path.startsWith('/templates') || path.startsWith('/copy-trading')) {
      keys.push('/copy-trading-management')
    }
    if (path.startsWith('/system-settings')) {
      keys.push('/system-settings')
    }
    setOpenKeys(keys)
  }, [location.pathname])
  
  const menuItems: MenuProps['items'] = [
    {
      key: '/announcements',
      icon: <NotificationOutlined />,
      label: t('menu.announcements') || '公告'
    },
    {
      key: '/accounts',
      icon: <WalletOutlined />,
      label: t('menu.accounts')
    },
    {
      key: '/copy-trading-management',
      icon: <AppstoreOutlined />,
      label: t('menu.copyTrading'),
      children: [
        {
          key: '/copy-trading',
          icon: <LinkOutlined />,
          label: t('menu.copyTradingConfig')
        },
        {
          key: '/leaders',
          icon: <UserOutlined />,
          label: t('menu.leaders')
        },
        {
          key: '/templates',
          icon: <FileTextOutlined />,
          label: t('menu.templates')
        }
      ]
    },
    {
      key: '/positions',
      icon: <UnorderedListOutlined />,
      label: t('menu.positions')
    },
    {
      key: '/statistics',
      icon: <BarChartOutlined />,
      label: t('menu.statistics')
    },
    {
      key: '/users',
      icon: <TeamOutlined />,
      label: t('menu.users')
    },
    {
      key: '/system-settings',
      icon: <SettingOutlined />,
      label: t('menu.systemSettings') || '系统管理',
      children: [
        {
          key: '/system-settings',
          icon: <SettingOutlined />,
          label: t('menu.systemOverview') || '通用设置'
        },
        {
          key: '/system-settings/rpc-nodes',
          icon: <ApiOutlined />,
          label: t('menu.rpcNodes') || 'RPC节点管理'
        },
        {
          key: '/system-settings/api-health',
          icon: <CheckCircleOutlined />,
          label: t('menu.apiHealth') || 'API健康'
        }
      ]
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('menu.logout')
    }
  ]
  
  const handleLogout = () => {
    removeToken()
    // 断开 WebSocket 连接
    wsManager.disconnect()
    navigate('/login', { replace: true })
  }
  
  const handleLogoutConfirm = () => {
    Modal.confirm({
      title: t('menu.logoutConfirm'),
      content: t('menu.logoutConfirmDesc'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: () => {
        handleLogout()
        if (isMobile) {
          setMobileMenuOpen(false)
        }
      }
    })
  }
  
  const handleMenuClick = ({ key }: { key: string }) => {
    // 如果是父菜单，不导航（但 /system-settings 作为子菜单项时可以导航）
    if (key === '/copy-trading-management') {
      return
    }
    
    // 处理退出登录
    if (key === 'logout') {
      handleLogoutConfirm()
      return
    }
    
    navigate(key)
    if (isMobile) {
      setMobileMenuOpen(false)
    }
  }
  
  const handleOpenChange = (keys: string[]) => {
    setOpenKeys(keys)
  }
  
  if (isMobile) {
    // 移动端布局
    return (
      <AntLayout style={{ minHeight: '100vh' }}>
        <Header style={{ 
          background: '#001529', 
          padding: '0 16px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Logo 
              size="normal" 
              darkMode={true}
            />
            <a
              href={getGitHubTagUrl()}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => {
                const version = getVersionText()
                if (version === 'dev') {
                  e.preventDefault()
                }
              }}
              style={{
                color: 'rgba(255, 255, 255, 0.7)',
                fontSize: '12px',
                fontWeight: 'normal',
                textDecoration: 'none',
                cursor: getVersionText() === 'dev' ? 'default' : 'pointer'
              }}
              title={getVersionText() === 'dev' ? '' : '查看版本发布'}
            >
              v{getVersionText()}
            </a>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <a
              href="https://github.com/WrBug/PolyHermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '16px', display: 'flex', alignItems: 'center' }}
              title="GitHub"
            >
              <GithubOutlined />
            </a>
            <a
              href="https://x.com/polyhermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '16px', display: 'flex', alignItems: 'center' }}
              title="Twitter"
            >
              <TwitterOutlined />
            </a>
            <a
              href="https://t.me/polyhermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '16px', display: 'flex', alignItems: 'center' }}
              title="Telegram 交流群"
            >
              <SendOutlined />
            </a>
            <Button
              type="text"
              icon={<MenuOutlined />}
              style={{ color: '#fff', marginLeft: '4px' }}
              onClick={() => setMobileMenuOpen(true)}
            />
          </div>
        </Header>
        <Content style={{ 
          padding: '12px 8px', 
          background: '#f0f2f5',
          minHeight: 'calc(100vh - 64px)'
        }}>
          {children}
        </Content>
        <Drawer
          title={t('menu.navigation')}
          placement="left"
          onClose={() => setMobileMenuOpen(false)}
          open={mobileMenuOpen}
          bodyStyle={{ padding: 0 }}
        >
          <Menu
            mode="inline"
            selectedKeys={getSelectedKeys()}
            openKeys={openKeys}
            onOpenChange={handleOpenChange}
            items={menuItems}
            onClick={handleMenuClick}
            style={{ border: 'none' }}
          />
        </Drawer>
      </AntLayout>
    )
  }
  
  // 桌面端布局
  return (
    <AntLayout style={{ height: '100vh', overflow: 'hidden' }}>
      <Sider 
        width={200} 
        style={{ 
          background: '#001529',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          overflow: 'hidden'
        }}
      >
        <div style={{ 
          padding: '16px',
          color: '#fff',
          flexShrink: 0,
          borderBottom: '1px solid rgba(255, 255, 255, 0.1)'
        }}>
          <div style={{ 
            fontSize: '18px',
            fontWeight: 'bold',
            marginBottom: '12px',
            textAlign: 'center',
            display: 'flex',
            alignItems: 'flex-end',
            justifyContent: 'center',
            gap: '6px'
          }}>
            <span>PolyHermes</span>
            <a
              href={getGitHubTagUrl()}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => {
                const version = getVersionText()
                if (version === 'dev') {
                  e.preventDefault()
                }
              }}
              style={{
                color: 'rgba(255, 255, 255, 0.7)',
                fontSize: '12px',
                fontWeight: 'normal',
                textDecoration: 'none',
                cursor: getVersionText() === 'dev' ? 'default' : 'pointer',
                lineHeight: '1',
                paddingBottom: '2px'
              }}
              title={getVersionText() === 'dev' ? '' : '查看版本发布'}
            >
              v{getVersionText()}
            </a>
          </div>
          <div style={{ 
            display: 'flex', 
            gap: '12px', 
            alignItems: 'center',
            justifyContent: 'center'
          }}>
            <a
              href="https://github.com/WrBug/PolyHermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '18px', display: 'flex', alignItems: 'center' }}
              title="GitHub"
            >
              <GithubOutlined />
            </a>
            <a
              href="https://x.com/polyhermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '18px', display: 'flex', alignItems: 'center' }}
              title="Twitter"
            >
              <TwitterOutlined />
            </a>
            <a
              href="https://t.me/polyhermes"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#fff', fontSize: '18px', display: 'flex', alignItems: 'center' }}
              title="Telegram 交流群"
            >
              <SendOutlined />
            </a>
          </div>
        </div>
        <Menu
          mode="inline"
          selectedKeys={getSelectedKeys()}
          openKeys={openKeys}
          onOpenChange={handleOpenChange}
          items={menuItems}
          onClick={handleMenuClick}
          style={{ 
            height: 'calc(100vh - 100px)', 
            borderRight: 0,
            overflowY: 'auto'
          }}
        />
      </Sider>
      <AntLayout style={{ marginLeft: 200, height: '100vh' }}>
        <Content style={{ 
          padding: '24px', 
          background: '#f0f2f5', 
          height: '100vh',
          overflowY: 'auto'
        }}>
          {children}
        </Content>
      </AntLayout>
    </AntLayout>
  )
}

export default Layout

