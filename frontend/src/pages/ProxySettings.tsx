import { useEffect, useState } from 'react'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import ProxyCheckResultAlert, { type ProxyCheckResponse } from '../components/ProxyCheckResultAlert'

const { Title } = Typography

interface ProxyConfig {
  id?: number
  type: string
  enabled: boolean
  host?: string
  port?: number
  username?: string
  subscriptionUrl?: string
  lastSubscriptionUpdate?: number
  createdAt: number
  updatedAt: number
}

const ProxySettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(false)
  const [checkResult, setCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentConfig, setCurrentConfig] = useState<ProxyConfig | null>(null)
  
  useEffect(() => {
    fetchConfig()
  }, [])
  
  const fetchConfig = async () => {
    try {
      const response = await apiService.proxyConfig.get()
      if (response.data.code === 0) {
        const data = response.data.data
        setCurrentConfig(data)
        if (data) {
          form.setFieldsValue({
            enabled: data.enabled,
            host: data.host || '',
            port: data.port || undefined,
            username: data.username || '',
            password: '',  // 密码不预填充
          })
        } else {
          form.resetFields()
        }
      } else {
        message.error(response.data.msg || t('proxySettings.getFailed') || '获取代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('proxySettings.getFailed') || '获取代理配置失败')
    }
  }
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const requestData: any = {
        enabled: values.enabled || false,
        host: values.host,
        port: values.port,
        username: values.username || undefined,
      }
      
      // 只有在输入了新密码时才包含密码字段
      if (values.password && values.password.trim()) {
        requestData.password = values.password
      }
      
      const response = await apiService.proxyConfig.saveHttp(requestData)
      if (response.data.code === 0) {
        message.success(t('proxySettings.saveSuccess') || '保存配置成功')
        setCheckResult(null)
        fetchConfig()
      } else {
        message.error(response.data.msg || t('proxySettings.saveFailed') || '保存配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('proxySettings.saveFailed') || '保存配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  const handleCheck = async () => {
    setChecking(true)
    setCheckResult(null)
    try {
      const response = await apiService.proxyConfig.check()
      if (response.data.code === 0 && response.data.data) {
        setCheckResult(response.data.data)
      } else {
        setCheckResult({
          success: false,
          message: response.data.msg || t('proxySettings.checkFailed') || '代理检查失败'
        })
      }
    } catch (error: any) {
      setCheckResult({
        success: false,
        message: error.message || t('proxySettings.checkFailed') || '代理检查失败'
      })
    } finally {
      setChecking(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('proxySettings.title') || '代理设置'}</Title>
      </div>
      
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('proxySettings.enabled') || '启用代理'}
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.host') || '代理主机'}
            name="host"
            rules={[
              { required: true, message: t('proxySettings.hostRequired') || '请输入代理主机地址' },
              { pattern: /^[\w\.-]+$/, message: t('proxySettings.hostInvalid') || '请输入有效的主机地址' }
            ]}
          >
            <Input placeholder={t('proxySettings.hostPlaceholder') || '例如：127.0.0.1 或 proxy.example.com'} />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.port') || '代理端口'}
            name="port"
            rules={[
              { required: true, message: t('proxySettings.portRequired') || '请输入代理端口' },
              { type: 'number', min: 1, max: 65535, message: t('proxySettings.portInvalid') || '端口必须在 1-65535 之间' }
            ]}
          >
            <InputNumber
              min={1}
              max={65535}
              style={{ width: '100%' }}
              placeholder={t('proxySettings.portPlaceholder') || '例如：8888'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.username') || '代理用户名（可选）'}
            name="username"
          >
            <Input placeholder={t('proxySettings.usernamePlaceholder') || '如果代理需要认证，请输入用户名'} />
          </Form.Item>
          
          <Form.Item
            label={t('proxySettings.password') || '代理密码（可选）'}
            name="password"
            help={currentConfig ? (t('proxySettings.passwordHelpUpdate') || '留空则不更新密码，输入新密码则更新') : (t('proxySettings.passwordHelp') || '如果代理需要认证，请输入密码')}
          >
            <Input.Password placeholder={currentConfig ? (t('proxySettings.passwordPlaceholderUpdate') || '留空则不更新密码') : (t('proxySettings.passwordPlaceholder') || '如果代理需要认证，请输入密码')} />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('common.save') || '保存配置'}
              </Button>
              <Button
                icon={<CheckCircleOutlined />}
                onClick={handleCheck}
                loading={checking}
              >
                {t('proxySettings.check') || '检查代理'}
              </Button>
              {checkResult && (
                <Button
                  icon={<ReloadOutlined />}
                  onClick={fetchConfig}
                >
                  {t('common.refresh') || '刷新配置'}
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>
        
        {checkResult && (
          <ProxyCheckResultAlert result={checkResult} style={{ marginTop: '16px' }} />
        )}
      </Card>
    </div>
  )
}

export default ProxySettings

