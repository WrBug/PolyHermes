import { useEffect, useState } from 'react'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space, Alert, Badge, Spin, Row, Col } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined, GlobalOutlined, SettingOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'

const { Title, Text } = Typography

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

interface ProxyCheckResponse {
  success: boolean
  message: string
  responseTime?: number
  latency?: number  // 延迟（毫秒）
}

interface ApiHealthStatus {
  name: string
  url: string
  status: string
  message: string
  responseTime?: number
}

const SystemSettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [autoRedeemForm] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(false)
  const [checkResult, setCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentConfig, setCurrentConfig] = useState<ProxyConfig | null>(null)
  const [apiHealthStatus, setApiHealthStatus] = useState<ApiHealthStatus[]>([])
  const [checkingApiHealth, setCheckingApiHealth] = useState(false)
  const [autoRedeemLoading, setAutoRedeemLoading] = useState(false)
  const [builderApiKeyConfigured, setBuilderApiKeyConfigured] = useState<boolean>(false)
  
  useEffect(() => {
    fetchConfig()
    checkApiHealth()
    fetchSystemConfig()
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
        message.error(response.data.msg || '获取代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取代理配置失败')
    }
  }
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const response = await apiService.proxyConfig.saveHttp({
        enabled: values.enabled || false,
        host: values.host,
        port: values.port,
        username: values.username || undefined,
        password: values.password || undefined,  // 如果密码为空，则不更新密码
      })
      if (response.data.code === 0) {
        message.success('保存代理配置成功。新配置将立即生效，已建立的 WebSocket 连接需要重新连接才能使用新代理。')
        fetchConfig()
        setCheckResult(null)  // 清除检查结果
        // 自动刷新 API 健康状态，验证新配置是否生效
        setTimeout(() => {
          checkApiHealth()
        }, 1000)
      } else {
        message.error(response.data.msg || '保存代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '保存代理配置失败')
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
        const result = response.data.data
        setCheckResult(result)
        if (result.success) {
          message.success(`代理检查成功：${result.message}${result.responseTime ? ` (响应时间: ${result.responseTime}ms)` : ''}`)
        } else {
          message.warning(`代理检查失败：${result.message}`)
        }
      } else {
        message.error(response.data.msg || '代理检查失败')
      }
    } catch (error: any) {
      message.error(error.message || '代理检查失败')
    } finally {
      setChecking(false)
    }
  }
  
  const checkApiHealth = async () => {
    setCheckingApiHealth(true)
    try {
      const response = await apiService.proxyConfig.checkApiHealth()
      if (response.data.code === 0 && response.data.data) {
        setApiHealthStatus(response.data.data.apis)
      } else {
        message.error(response.data.msg || 'API 健康检查失败')
      }
    } catch (error: any) {
      message.error(error.message || 'API 健康检查失败')
    } finally {
      setCheckingApiHealth(false)
    }
  }
  
  const getStatusColor = (status: string) => {
    if (status === 'success') {
      return '#52c41a'
    } else if (status === 'skipped') {
      return '#999'
    } else {
      return '#ff4d4f'
    }
  }
  
  const getStatusText = (status: string) => {
    if (status === 'success') {
      return '正常'
    } else if (status === 'skipped') {
      return '未配置'
    } else {
      return '异常'
    }
  }
  
  const fetchSystemConfig = async () => {
    try {
      const response = await apiService.systemConfig.get()
      if (response.data.code === 0 && response.data.data) {
        const config = response.data.data
        setBuilderApiKeyConfigured(config.builderApiKeyConfigured)
        autoRedeemForm.setFieldsValue({
          autoRedeem: config.autoRedeem
        })
      }
    } catch (error: any) {
      console.error('获取系统配置失败:', error)
    }
  }
  
  const handleAutoRedeemSubmit = async (values: { autoRedeem: boolean }) => {
    setAutoRedeemLoading(true)
    try {
      const response = await apiService.systemConfig.updateAutoRedeem({ enabled: values.autoRedeem })
      if (response.data.code === 0) {
        message.success(t('systemSettings.autoRedeem.saveSuccess') || '自动赎回配置已更新')
        fetchSystemConfig()
      } else {
        message.error(response.data.msg || t('systemSettings.autoRedeem.saveFailed') || '更新自动赎回配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('systemSettings.autoRedeem.saveFailed') || '更新自动赎回配置失败')
    } finally {
      setAutoRedeemLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>系统管理</Title>
      </div>
      
      <Card 
        title={
          <Space>
            <GlobalOutlined />
            <span>API 健康状态</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={checkApiHealth}
            loading={checkingApiHealth}
            size="small"
          >
            刷新
          </Button>
        }
      >
        <Spin spinning={checkingApiHealth}>
          <Row gutter={[16, 16]}>
            {apiHealthStatus.map((item, index) => (
              <Col 
                key={index}
                xs={24} 
                sm={12} 
                md={12} 
                lg={8} 
                xl={6}
              >
                {isMobile ? (
                  // 移动端：一行显示，只保留名称、延迟、状态点（不显示文字）
                  <Card
                    size="small"
                    style={{
                      borderLeft: `4px solid ${getStatusColor(item.status)}`,
                    }}
                    bodyStyle={{ padding: '12px' }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '8px' }}>
                      <Text strong style={{ fontSize: '14px' }}>
                        {item.name}
                      </Text>
                      <Space>
                        {item.responseTime !== undefined && item.responseTime !== null && (
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            <Text strong style={{ color: '#1890ff' }}>{item.responseTime}ms</Text>
                          </Text>
                        )}
                        <Badge 
                          status={item.status === 'success' ? 'success' : item.status === 'skipped' ? 'default' : 'error'}
                        />
                      </Space>
                    </div>
                  </Card>
                ) : (
                  // 桌面端：保持原有卡片布局
                  <Card
                    size="small"
                    style={{
                      borderLeft: `4px solid ${getStatusColor(item.status)}`,
                      height: '100%'
                    }}
                    bodyStyle={{ padding: '16px' }}
                  >
                    <Space direction="vertical" size="small" style={{ width: '100%' }}>
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <Text strong style={{ fontSize: '14px' }}>
                          {item.name}
                        </Text>
                        <Badge 
                          status={item.status === 'success' ? 'success' : item.status === 'skipped' ? 'default' : 'error'} 
                          text={getStatusText(item.status)}
                        />
                      </div>
                      
                      <div style={{ marginTop: '8px' }}>
                        <Text type="secondary" style={{ fontSize: '12px', wordBreak: 'break-all' }}>
                          {item.url}
                        </Text>
                      </div>
                      
                      {item.message && item.message !== '连接成功' && (
                        <div style={{ marginTop: '8px' }}>
                          <Text 
                            type={item.status === 'success' ? 'success' : item.status === 'skipped' ? 'secondary' : 'danger'}
                            style={{ fontSize: '13px' }}
                          >
                            {item.message}
                          </Text>
                        </div>
                      )}
                      
                      {item.responseTime !== undefined && item.responseTime !== null && (
                        <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center' }}>
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            延迟: <Text strong style={{ color: '#1890ff' }}>{item.responseTime}ms</Text>
                          </Text>
                        </div>
                      )}
                    </Space>
                  </Card>
                )}
              </Col>
            ))}
          </Row>
          
          {apiHealthStatus.length === 0 && !checkingApiHealth && (
            <div style={{ textAlign: 'center', padding: '40px 0', color: '#999' }}>
              <Text type="secondary">暂无 API 状态信息</Text>
            </div>
          )}
        </Spin>
      </Card>
      
      <Card title="代理设置" style={{ marginBottom: '16px' }}>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label="启用代理"
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label="代理主机"
            name="host"
            rules={[
              { required: true, message: '请输入代理主机地址' },
              { pattern: /^[\w\.-]+$/, message: '请输入有效的主机地址' }
            ]}
          >
            <Input placeholder="例如：127.0.0.1 或 proxy.example.com" />
          </Form.Item>
          
          <Form.Item
            label="代理端口"
            name="port"
            rules={[
              { required: true, message: '请输入代理端口' },
              { type: 'number', min: 1, max: 65535, message: '端口必须在 1-65535 之间' }
            ]}
          >
            <InputNumber
              min={1}
              max={65535}
              style={{ width: '100%' }}
              placeholder="例如：8888"
            />
          </Form.Item>
          
          <Form.Item
            label="代理用户名（可选）"
            name="username"
          >
            <Input placeholder="如果代理需要认证，请输入用户名" />
          </Form.Item>
          
          <Form.Item
            label="代理密码（可选）"
            name="password"
            help={currentConfig ? "留空则不更新密码，输入新密码则更新" : "如果代理需要认证，请输入密码"}
          >
            <Input.Password placeholder={currentConfig ? "留空则不更新密码" : "如果代理需要认证，请输入密码"} />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                保存配置
              </Button>
              <Button
                icon={<CheckCircleOutlined />}
                onClick={handleCheck}
                loading={checking}
              >
                检查代理
              </Button>
              {checkResult && (
                <Button
                  icon={<ReloadOutlined />}
                  onClick={fetchConfig}
                >
                  刷新配置
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>
        
        {checkResult && (
          <Alert
            type={checkResult.success ? 'success' : 'error'}
            message={checkResult.success ? '代理检查成功' : '代理检查失败'}
            description={
              <div>
                <Text>{checkResult.message}</Text>
                {(checkResult.responseTime !== undefined || checkResult.latency !== undefined) && (
                  <div style={{ marginTop: '8px' }}>
                    <Text type="secondary">
                      延迟: {(checkResult.latency ?? checkResult.responseTime) ?? 0}ms
                    </Text>
                  </div>
                )}
              </div>
            }
            style={{ marginTop: '16px' }}
            showIcon
          />
        )}
      </Card>
      
      <Card 
        title={
          <Space>
            <SettingOutlined />
            <span>{t('systemSettings.autoRedeem.title') || '自动赎回配置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Form
          form={autoRedeemForm}
          layout="vertical"
          onFinish={handleAutoRedeemSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('systemSettings.autoRedeem.label') || '自动赎回'}
            name="autoRedeem"
            tooltip={t('systemSettings.autoRedeem.tooltip') || '开启后，系统会自动赎回可赎回的仓位。需要配置 Builder API Key 才能生效'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {!builderApiKeyConfigured && (
            <Alert
              message={t('systemSettings.autoRedeem.builderApiKeyNotConfigured') || 'Builder API Key 未配置'}
              description={
                <span>
                  {t('systemSettings.autoRedeem.builderApiKeyNotConfiguredDesc') || '自动赎回功能需要配置 Builder API Key 才能生效。'}
                  <Button
                    type="link"
                    size="small"
                    onClick={() => window.location.href = '/system-settings/builder-api-key'}
                    style={{ padding: 0, marginLeft: '8px' }}
                  >
                    {t('systemSettings.autoRedeem.goToConfigure') || '前往配置'}
                  </Button>
                </span>
              }
              type="warning"
              showIcon
              style={{ marginBottom: '16px' }}
            />
          )}
          
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={autoRedeemLoading}
            >
              {t('common.save') || '保存配置'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default SystemSettings

