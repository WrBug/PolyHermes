import { useEffect, useState } from 'react'
import { Card, Form, Button, Input, message, Typography, Space, Alert } from 'antd'
import { SaveOutlined, LinkOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import type { SystemConfig, BuilderApiKeyUpdateRequest } from '../types'

const { Title, Paragraph, Text } = Typography

const BuilderApiKeySettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [builderApiKeyForm] = Form.useForm()
  const [builderApiKeyConfig, setBuilderApiKeyConfig] = useState<SystemConfig | null>(null)
  const [builderApiKeyLoading, setBuilderApiKeyLoading] = useState(false)
  
  useEffect(() => {
    fetchBuilderApiKeyConfig()
  }, [])
  
  const fetchBuilderApiKeyConfig = async () => {
    try {
      const response = await apiService.systemConfig.get()
      if (response.data.code === 0 && response.data.data) {
        const config = response.data.data
        setBuilderApiKeyConfig(config)
        // 如果已配置，输入框留空（不显示***）
        builderApiKeyForm.setFieldsValue({
          builderApiKey: '',
          builderSecret: '',
          builderPassphrase: '',
        })
      } else {
        message.error(response.data.msg || t('builderApiKey.getFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('builderApiKey.getFailed'))
    }
  }
  
  const handleBuilderApiKeySubmit = async (values: BuilderApiKeyUpdateRequest) => {
    setBuilderApiKeyLoading(true)
    try {
      // 只发送非空字段（如果字段为空且已配置，表示不修改，不发送该字段）
      const updateData: BuilderApiKeyUpdateRequest = {}
      if (values.builderApiKey && values.builderApiKey.trim()) {
        updateData.builderApiKey = values.builderApiKey.trim()
      }
      if (values.builderSecret && values.builderSecret.trim()) {
        updateData.builderSecret = values.builderSecret.trim()
      }
      if (values.builderPassphrase && values.builderPassphrase.trim()) {
        updateData.builderPassphrase = values.builderPassphrase.trim()
      }
      
      // 如果所有字段都为空，提示用户
      if (!updateData.builderApiKey && !updateData.builderSecret && !updateData.builderPassphrase) {
        message.warning(t('builderApiKey.noChanges') || '没有需要更新的字段')
        setBuilderApiKeyLoading(false)
        return
      }
      
      const response = await apiService.systemConfig.updateBuilderApiKey(updateData)
      if (response.data.code === 0) {
        message.success(t('builderApiKey.saveSuccess'))
        fetchBuilderApiKeyConfig()
      } else {
        message.error(response.data.msg || t('builderApiKey.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('builderApiKey.saveFailed'))
    } finally {
      setBuilderApiKeyLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('builderApiKey.title')}</Title>
      </div>
      
      <Card>
        <Alert
          message={t('builderApiKey.alertTitle')}
          description={
            <div>
              <Paragraph style={{ marginBottom: '8px' }}>
                {t('builderApiKey.description')}
              </Paragraph>
              <Paragraph style={{ marginBottom: '8px' }}>
                <Text strong>{t('builderApiKey.purposeTitle')}</Text>
                <ul style={{ marginTop: '8px', marginBottom: 0, paddingLeft: '20px' }}>
                  <li>{t('builderApiKey.purpose1')}</li>
                  <li>{t('builderApiKey.purpose2')}</li>
                  <li>{t('builderApiKey.purpose3')}</li>
                </ul>
              </Paragraph>
              <Paragraph style={{ marginBottom: 0 }}>
                <Text strong>{t('builderApiKey.getApiKey')}</Text>
                <Space style={{ marginLeft: '8px' }}>
                  <a 
                    href="https://polymarket.com/settings?tab=builder" 
                    target="_blank" 
                    rel="noopener noreferrer"
                  >
                    <LinkOutlined /> {t('builderApiKey.openSettings')}
                  </a>
                </Space>
              </Paragraph>
            </div>
          }
          type="info"
          showIcon
          style={{ marginBottom: '24px' }}
        />
        
        <Form
          form={builderApiKeyForm}
          layout="vertical"
          onFinish={handleBuilderApiKeySubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label={t('builderApiKey.apiKey')}
            name="builderApiKey"
            help={builderApiKeyConfig?.builderApiKeyConfigured ? t('builderApiKey.apiKeyHelp') : t('builderApiKey.apiKeyPlaceholder')}
          >
            <Input 
              placeholder={builderApiKeyConfig?.builderApiKeyConfigured ? t('builderApiKey.apiKeyHelp') : t('builderApiKey.apiKeyPlaceholder')}
            />
          </Form.Item>
          
          <Form.Item
            label={t('builderApiKey.secret')}
            name="builderSecret"
            help={builderApiKeyConfig?.builderSecretConfigured ? t('builderApiKey.secretHelp') : t('builderApiKey.secretPlaceholder')}
          >
            <Input 
              placeholder={builderApiKeyConfig?.builderSecretConfigured ? t('builderApiKey.secretHelp') : t('builderApiKey.secretPlaceholder')}
            />
          </Form.Item>
          
          <Form.Item
            label={t('builderApiKey.passphrase')}
            name="builderPassphrase"
            help={builderApiKeyConfig?.builderPassphraseConfigured ? t('builderApiKey.passphraseHelp') : t('builderApiKey.passphrasePlaceholder')}
          >
            <Input 
              placeholder={builderApiKeyConfig?.builderPassphraseConfigured ? t('builderApiKey.passphraseHelp') : t('builderApiKey.passphrasePlaceholder')}
            />
          </Form.Item>
          
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={builderApiKeyLoading}
            >
              {t('common.save') || '保存配置'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default BuilderApiKeySettings

