import { useState } from 'react'
import { Form, Input, Button, Space } from 'antd'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import { isValidWalletAddress } from '../utils'

interface LeaderAddFormProps {
  form: any
  onSuccess?: (leaderId: number) => void
  onCancel?: () => void
  showCancelButton?: boolean
}

const LeaderAddForm: React.FC<LeaderAddFormProps> = ({
  form,
  onSuccess,
  onCancel,
  showCancelButton = true
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const response = await apiService.leaders.add({
        leaderAddress: values.leaderAddress.trim(),
        leaderName: values.leaderName?.trim() || undefined,
        remark: values.remark?.trim() || undefined,
        website: values.website?.trim() || undefined
      })
      
      if (response.data.code === 0) {
        if (response.data.data && onSuccess) {
          onSuccess(response.data.data.id)
        }
        return Promise.resolve()
      } else {
        return Promise.reject(new Error(response.data.msg || t('leaderAdd.addFailed') || '添加 Leader 失败'))
      }
    } catch (error: any) {
      return Promise.reject(error)
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <Form
      form={form}
      layout="vertical"
      onFinish={handleSubmit}
      size={isMobile ? 'middle' : 'large'}
    >
      <Form.Item
        label={t('leaderAdd.leaderAddress') || 'Leader 钱包地址'}
        name="leaderAddress"
        rules={[
          { required: true, message: t('leaderAdd.leaderAddressRequired') || '请输入 Leader 钱包地址' },
          {
            validator: (_, value) => {
              if (!value) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressRequired') || '请输入 Leader 钱包地址'))
              }
              if (!isValidWalletAddress(value.trim())) {
                return Promise.reject(new Error(t('leaderAdd.leaderAddressInvalid') || '钱包地址格式不正确（必须是 0x 开头的 42 位地址）'))
              }
              return Promise.resolve()
            }
          }
        ]}
        tooltip={t('leaderAdd.leaderAddressTooltip') || '被跟单者的钱包地址，系统将监控该地址的交易并自动跟单'}
      >
        <Input placeholder="0x..." style={{ fontFamily: 'monospace' }} />
      </Form.Item>
      
      <Form.Item
        label={t('leaderAdd.leaderName') || 'Leader 名称'}
        name="leaderName"
        tooltip={t('leaderAdd.leaderNameTooltip') || '可选，用于标识 Leader，方便管理'}
      >
        <Input placeholder={t('leaderAdd.leaderNamePlaceholder') || '可选，用于标识 Leader'} />
      </Form.Item>
      
      <Form.Item
        label={t('leaderAdd.remark') || 'Leader 备注'}
        name="remark"
        tooltip={t('leaderAdd.remarkTooltip') || '可选，用于记录 Leader 的备注信息'}
      >
        <Input.TextArea 
          placeholder={t('leaderAdd.remarkPlaceholder') || '可选，用于记录 Leader 的备注信息'} 
          rows={3}
          maxLength={500}
          showCount
        />
      </Form.Item>
      
      <Form.Item
        label={t('leaderAdd.website') || 'Leader 网站'}
        name="website"
        tooltip={t('leaderAdd.websiteTooltip') || '可选，Leader 的网站链接'}
        rules={[
          {
            type: 'url',
            message: t('leaderAdd.websiteInvalid') || '请输入有效的 URL 地址'
          }
        ]}
      >
        <Input placeholder={t('leaderAdd.websitePlaceholder') || '可选，例如：https://example.com'} />
      </Form.Item>
      
      <Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            size={isMobile ? 'middle' : 'large'}
          >
            {t('leaderAdd.add') || '添加 Leader'}
          </Button>
          {showCancelButton && onCancel && (
            <Button onClick={onCancel}>
              {t('common.cancel')}
            </Button>
          )}
        </Space>
      </Form.Item>
    </Form>
  )
}

export default LeaderAddForm

