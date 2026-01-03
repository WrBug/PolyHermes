import { useEffect, useState } from 'react'
import { Card, Button, Typography, Space, Badge, Spin, Row, Col } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'

const { Title, Text } = Typography

interface ApiHealthStatus {
  name: string
  url: string
  status: string
  message: string
  responseTime?: number
}

const ApiHealthStatus: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [apiHealthStatus, setApiHealthStatus] = useState<ApiHealthStatus[]>([])
  const [checkingApiHealth, setCheckingApiHealth] = useState(false)
  
  useEffect(() => {
    checkApiHealth()
  }, [])
  
  const checkApiHealth = async () => {
    setCheckingApiHealth(true)
    try {
      const response = await apiService.proxyConfig.checkApiHealth()
      if (response.data.code === 0 && response.data.data) {
        setApiHealthStatus(response.data.data.apis)
      } else {
        // message.error(response.data.msg || 'API 健康检查失败')
      }
    } catch (error: any) {
      // message.error(error.message || 'API 健康检查失败')
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
      return t('apiHealthStatus.normal') || '正常'
    } else if (status === 'skipped') {
      return t('apiHealthStatus.notConfigured') || '未配置'
    } else {
      return t('apiHealthStatus.abnormal') || '异常'
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('apiHealthStatus.title') || 'API 健康状态'}</Title>
      </div>
      
      <Card 
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={checkApiHealth}
            loading={checkingApiHealth}
            size="small"
          >
            {t('common.refresh') || '刷新'}
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
                  <Card
                    size="small"
                    style={{
                      borderLeft: `4px solid ${getStatusColor(item.status)}`,
                    }}
                    bodyStyle={{ padding: '12px' }}
                  >
                    <Space direction="vertical" size="small" style={{ width: '100%' }}>
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
                            text={getStatusText(item.status)}
                        />
                      </Space>
                    </div>
                      
                      <div>
                        <Text type="secondary" style={{ fontSize: '12px', wordBreak: 'break-all' }}>
                          {item.url}
                        </Text>
                      </div>
                      
                      {item.message && item.message !== '连接成功' && (
                        <div>
                          <Text 
                            type={item.status === 'success' ? 'success' : item.status === 'skipped' ? 'secondary' : 'danger'}
                            style={{ fontSize: '12px' }}
                          >
                            {item.message}
                          </Text>
                        </div>
                      )}
                    </Space>
                  </Card>
                ) : (
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
                        <div style={{ marginTop: '8px' }}>
                          <Text type="secondary" style={{ fontSize: '12px' }}>
                            {t('apiHealthStatus.responseTime') || '响应时间'}: <Text strong style={{ color: '#1890ff' }}>{item.responseTime}ms</Text>
                          </Text>
                        </div>
                      )}
                    </Space>
                  </Card>
                )}
              </Col>
            ))}
          </Row>
        </Spin>
      </Card>
    </div>
  )
}

export default ApiHealthStatus

