import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography, Space, Alert, Checkbox } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useAccountStore } from '../store/accountStore'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const AccountEdit: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const accountId = searchParams.get('id')
  
  const { fetchAccountDetail, updateAccount, loading } = useAccountStore()
  const [form] = Form.useForm()
  const [account, setAccount] = useState<any>(null)
  const [loadingDetail, setLoadingDetail] = useState(true)
  
  useEffect(() => {
    if (accountId) {
      loadAccountDetail()
    } else {
      message.error('账户ID不能为空')
      navigate('/accounts')
    }
  }, [accountId])
  
  const loadAccountDetail = async () => {
    if (!accountId) return
    
    setLoadingDetail(true)
    try {
      const accountData = await fetchAccountDetail(Number(accountId))
      setAccount(accountData)
      
      // 设置表单初始值
      form.setFieldsValue({
        accountName: accountData.accountName || '',
        isDefault: accountData.isDefault || false
      })
    } catch (error: any) {
      message.error(error.message || '获取账户详情失败')
      navigate('/accounts')
    } finally {
      setLoadingDetail(false)
    }
  }
  
  const handleSubmit = async (values: any) => {
    if (!accountId) return
    
    try {
      // 构建更新请求
      const updateData: any = {
        accountId: Number(accountId),
        accountName: values.accountName || undefined,
        isDefault: values.isDefault || false
      }
      
      await updateAccount(updateData)
      
      message.success('更新账户成功')
      navigate(`/accounts/detail?id=${accountId}`)
    } catch (error: any) {
      message.error(error.message || '更新账户失败')
    }
  }
  
  if (loadingDetail) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <div>加载中...</div>
      </div>
    )
  }
  
  if (!account) {
    return null
  }
  
  return (
    <div style={{ 
      padding: isMobile ? '0' : undefined,
      margin: isMobile ? '0 -8px' : undefined
    }}>
      <div style={{ 
        marginBottom: isMobile ? '12px' : '16px',
        padding: isMobile ? '0 8px' : '0'
      }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate(`/accounts/detail?id=${accountId}`)}
          style={{ marginBottom: '16px' }}
          size={isMobile ? 'middle' : 'large'}
        >
          返回
        </Button>
        <Title level={isMobile ? 4 : 2} style={{ margin: 0, fontSize: isMobile ? '18px' : undefined }}>
          编辑账户
        </Title>
      </div>
      
      <Card style={{ 
        margin: isMobile ? '0 -8px' : '0',
        borderRadius: isMobile ? '0' : undefined
      }}>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label="账户名称"
            name="accountName"
          >
            <Input placeholder="账户名称（可选）" />
          </Form.Item>
          
          <Alert
            message="API Key 管理"
            description="API Key 由系统自动管理，无需手动更新。如需重新获取 API Key，请删除并重新导入账户。"
            type="info"
            showIcon
            style={{ marginBottom: '24px' }}
          />
          
          <Form.Item
            name="isDefault"
            valuePropName="checked"
          >
            <Checkbox>设为默认账户</Checkbox>
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
                style={isMobile ? { minHeight: '44px' } : undefined}
              >
                保存
              </Button>
              <Button 
                onClick={() => navigate(`/accounts/detail?id=${accountId}`)}
                size={isMobile ? 'middle' : 'large'}
                style={isMobile ? { minHeight: '44px' } : undefined}
              >
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default AccountEdit

