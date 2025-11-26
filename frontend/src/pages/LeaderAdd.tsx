import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Input, Button, Select, Switch, message, Typography, Space } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography
const { Option } = Select

const LeaderAdd: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  
  useEffect(() => {
    fetchAccounts()
  }, [fetchAccounts])
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const response = await apiService.leaders.add({
        leaderAddress: values.leaderAddress,
        leaderName: values.leaderName,
        accountId: values.accountId,
        category: values.category,
        enabled: values.enabled !== false
      })
      
      if (response.data.code === 0) {
        message.success('添加 Leader 成功')
        navigate('/leaders')
      } else {
        message.error(response.data.msg || '添加 Leader 失败')
      }
    } catch (error: any) {
      message.error(error.message || '添加 Leader 失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/leaders')}
          style={{ marginBottom: '16px' }}
        >
          返回
        </Button>
        <Title level={2} style={{ margin: 0 }}>添加 Leader</Title>
      </div>
      
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
          initialValues={{
            enabled: true
          }}
        >
          <Form.Item
            label="Leader 钱包地址"
            name="leaderAddress"
            rules={[
              { required: true, message: '请输入 Leader 钱包地址' },
              {
                pattern: /^0x[a-fA-F0-9]{40}$/,
                message: '钱包地址格式不正确'
              }
            ]}
          >
            <Input placeholder="0x..." />
          </Form.Item>
          
          <Form.Item
            label="Leader 名称"
            name="leaderName"
          >
            <Input placeholder="可选，用于标识 Leader" />
          </Form.Item>
          
          <Form.Item
            label="使用的账户"
            name="accountId"
            help="选择用于跟单此 Leader 的账户，不选择则使用默认账户"
          >
            <Select placeholder="选择账户（可选）" allowClear>
              {accounts.map(account => (
                <Option key={account.id} value={account.id}>
                  {account.accountName || account.walletAddress} {account.isDefault && '(默认)'}
                </Option>
              ))}
            </Select>
          </Form.Item>
          
          <Form.Item
            label="分类筛选"
            name="category"
            help="仅跟单该分类的交易，不选择则跟单所有分类"
          >
            <Select placeholder="选择分类（可选）" allowClear>
              <Option value="sports">Sports</Option>
              <Option value="crypto">Crypto</Option>
            </Select>
          </Form.Item>
          
          <Form.Item
            label="启用跟单"
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size={isMobile ? 'middle' : 'large'}
              >
                添加 Leader
              </Button>
              <Button onClick={() => navigate('/leaders')}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default LeaderAdd

