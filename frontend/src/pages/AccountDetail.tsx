import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Descriptions, Button, Space, Tag, Spin, message, Typography, Divider, Modal, Form, Input, Checkbox, Alert } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, EditOutlined } from '@ant-design/icons'
import { useAccountStore } from '../store/accountStore'
import type { Account } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const AccountDetail: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const accountId = searchParams.get('id')
  
  const { fetchAccountDetail, fetchAccountBalance, updateAccount } = useAccountStore()
  const [account, setAccount] = useState<Account | null>(null)
  const [balance, setBalance] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [balanceLoading, setBalanceLoading] = useState(false)
  const [editModalVisible, setEditModalVisible] = useState(false)
  const [editForm] = Form.useForm()
  const [editLoading, setEditLoading] = useState(false)
  
  useEffect(() => {
    if (accountId) {
      loadAccountDetail()
      loadBalance()
    } else {
      message.error('账户ID不能为空')
      navigate('/accounts')
    }
  }, [accountId])
  
  const loadAccountDetail = async () => {
    if (!accountId) return
    
    setLoading(true)
    try {
      const accountData = await fetchAccountDetail(Number(accountId))
      setAccount(accountData)
    } catch (error: any) {
      message.error(error.message || '获取账户详情失败')
      navigate('/accounts')
    } finally {
      setLoading(false)
    }
  }
  
  const loadBalance = async () => {
    if (!accountId) return
    
    setBalanceLoading(true)
    try {
      const balanceData = await fetchAccountBalance(Number(accountId))
      setBalance(balanceData.totalBalance || null)
    } catch (error: any) {
      console.error('获取余额失败:', error)
      // 余额查询失败不显示错误，只显示 "-"
      setBalance(null)
    } finally {
      setBalanceLoading(false)
    }
  }
  
  const handleEditSubmit = async (values: any) => {
    if (!account) return
    
    setEditLoading(true)
    try {
      // 构建更新请求，空字符串转换为 undefined（不修改）
      const updateData: any = {
        accountId: account.id,
        accountName: values.accountName || undefined,
        isDefault: values.isDefault || false
      }
      
      // 只有非空字符串才更新 API 凭证
      if (values.apiKey && values.apiKey.trim()) {
        updateData.apiKey = values.apiKey.trim()
      }
      if (values.apiSecret && values.apiSecret.trim()) {
        updateData.apiSecret = values.apiSecret.trim()
      }
      if (values.apiPassphrase && values.apiPassphrase.trim()) {
        updateData.apiPassphrase = values.apiPassphrase.trim()
      }
      
      await updateAccount(updateData)
      
      message.success('更新账户成功')
      setEditModalVisible(false)
      editForm.resetFields()
      
      // 刷新账户详情
      if (accountId) {
        await loadAccountDetail()
      }
    } catch (error: any) {
      message.error(error.message || '更新账户失败')
    } finally {
      setEditLoading(false)
    }
  }
  
  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
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
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        marginBottom: isMobile ? '12px' : '16px',
        flexWrap: 'wrap',
        gap: '12px',
        padding: isMobile ? '0 8px' : '0'
      }}>
        <Space wrap>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/accounts')}
            size={isMobile ? 'middle' : 'large'}
          >
            返回
          </Button>
          <Title level={isMobile ? 4 : 2} style={{ margin: 0, fontSize: isMobile ? '16px' : undefined }}>
            {account.accountName || `账户 ${account.id}`}
          </Title>
        </Space>
        <Space wrap style={{ width: isMobile ? '100%' : 'auto' }}>
          <Button
            icon={<ReloadOutlined />}
            onClick={loadBalance}
            loading={balanceLoading}
            size={isMobile ? 'middle' : 'large'}
            block={isMobile}
            style={isMobile ? { minHeight: '44px' } : undefined}
          >
            刷新余额
          </Button>
          <Button
            type="primary"
            icon={<EditOutlined />}
            onClick={() => {
              setEditModalVisible(true)
              editForm.setFieldsValue({
                accountName: account.accountName || '',
                apiKey: '',  // 不显示实际值，留空表示不修改
                apiSecret: '',  // 不显示实际值，留空表示不修改
                apiPassphrase: '',  // 不显示实际值，留空表示不修改
                isDefault: account.isDefault || false
              })
            }}
            size={isMobile ? 'middle' : 'large'}
            block={isMobile}
            style={isMobile ? { minHeight: '44px' } : undefined}
          >
            编辑
          </Button>
        </Space>
      </div>
      
      <Card style={{ 
        margin: isMobile ? '0 -8px' : '0',
        borderRadius: isMobile ? '0' : undefined
      }}>
        <Descriptions
          column={isMobile ? 1 : 2}
          bordered
          size={isMobile ? 'small' : 'middle'}
          style={{ fontSize: isMobile ? '14px' : undefined }}
        >
          <Descriptions.Item label="账户ID">
            {account.id}
          </Descriptions.Item>
          <Descriptions.Item label="账户名称">
            {account.accountName || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="钱包地址" span={isMobile ? 1 : 2}>
            <span style={{ 
              fontFamily: 'monospace', 
              fontSize: isMobile ? '11px' : '14px',
              wordBreak: 'break-all',
              lineHeight: '1.4',
              display: 'block'
            }}>
              {account.walletAddress}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="默认账户">
            <Tag color={account.isDefault ? 'gold' : 'default'}>
              {account.isDefault ? '是' : '否'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="账户余额">
            {balanceLoading ? (
              <Spin size="small" />
            ) : balance ? (
              <span style={{ fontWeight: 'bold', color: '#1890ff' }}>
                {balance} USDC
              </span>
            ) : (
              <span style={{ color: '#999' }}>-</span>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      
      <Divider />
      
      <Card 
        title="API 凭证配置" 
        style={{ 
          marginTop: isMobile ? '12px' : '16px',
          margin: isMobile ? '0 -8px' : '0',
          borderRadius: isMobile ? '0' : undefined
        }}
      >
        <Descriptions
          column={isMobile ? 1 : 2}
          bordered
          size={isMobile ? 'small' : 'middle'}
          style={{ fontSize: isMobile ? '14px' : undefined }}
        >
          <Descriptions.Item label="API Key">
            <Tag color={account.apiKeyConfigured ? 'success' : 'default'}>
              {account.apiKeyConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API Secret">
            <Tag color={account.apiSecretConfigured ? 'success' : 'default'}>
              {account.apiSecretConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API Passphrase">
            <Tag color={account.apiPassphraseConfigured ? 'success' : 'default'}>
              {account.apiPassphraseConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="配置状态">
            {account.apiKeyConfigured && account.apiSecretConfigured && account.apiPassphraseConfigured ? (
              <Tag color="success">完整配置</Tag>
            ) : (
              <Tag color="warning">部分配置</Tag>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      
      {(account.totalOrders !== undefined || account.totalPnl !== undefined || 
        account.activeOrders !== undefined || 
        account.completedOrders !== undefined || account.positionCount !== undefined) ? (
        <>
          <Divider style={{ margin: isMobile ? '12px 0' : '16px 0' }} />
          <Card 
            title="交易统计" 
            style={{ 
              marginTop: isMobile ? '12px' : '16px',
              margin: isMobile ? '0 -8px' : '0',
              borderRadius: isMobile ? '0' : undefined
            }}
          >
            <Descriptions
              column={isMobile ? 1 : 2}
              bordered
              size={isMobile ? 'small' : 'middle'}
              style={{ fontSize: isMobile ? '14px' : undefined }}
            >
              {account.totalOrders !== undefined && (
                <Descriptions.Item label="总订单数">
                  {account.totalOrders}
                </Descriptions.Item>
              )}
              {account.activeOrders !== undefined && (
                <Descriptions.Item label="活跃订单数">
                  <Tag color={account.activeOrders > 0 ? 'orange' : 'default'}>{account.activeOrders}</Tag>
                </Descriptions.Item>
              )}
              {account.completedOrders !== undefined && (
                <Descriptions.Item label="已完成订单数">
                  <Tag color="success">{account.completedOrders}</Tag>
                </Descriptions.Item>
              )}
              {account.positionCount !== undefined && (
                <Descriptions.Item label="持仓数量">
                  <Tag color={account.positionCount > 0 ? 'blue' : 'default'}>{account.positionCount}</Tag>
                </Descriptions.Item>
              )}
              {account.totalPnl !== undefined && (
                <Descriptions.Item label="总盈亏">
                  <span style={{ 
                    fontWeight: 'bold',
                    color: account.totalPnl.startsWith('-') ? '#ff4d4f' : '#52c41a'
                  }}>
                    {account.totalPnl} USDC
                  </span>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        </>
      ) : null}
      
      {/* 编辑账户 Modal */}
      <Modal
        title={account ? `编辑账户 - ${account.accountName || `账户 ${account.id}`}` : '编辑账户'}
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false)
          editForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        destroyOnClose
        maskClosable
        closable
      >
        {account ? (
          <Form
            form={editForm}
            layout="vertical"
            onFinish={handleEditSubmit}
            size={isMobile ? 'middle' : 'large'}
          >
            <Alert
              message="编辑提示"
              description="API 凭证字段留空表示不修改。如需更新 API 凭证，请输入新值；如需保持原值不变，请留空。"
              type="info"
              showIcon
              style={{ marginBottom: '24px' }}
            />
            
            <Form.Item
              label="账户名称"
              name="accountName"
            >
              <Input placeholder="账户名称（可选）" />
            </Form.Item>
            
            <Form.Item
              label="API Key"
              name="apiKey"
              help="留空表示不修改，输入新值将更新 API Key"
            >
              <Input.Password placeholder="留空表示不修改" />
            </Form.Item>
            
            <Form.Item
              label="API Secret"
              name="apiSecret"
              help="留空表示不修改，输入新值将更新 API Secret"
            >
              <Input.Password placeholder="留空表示不修改" />
            </Form.Item>
            
            <Form.Item
              label="API Passphrase"
              name="apiPassphrase"
              help="留空表示不修改，输入新值将更新 API Passphrase"
            >
              <Input.Password placeholder="留空表示不修改" />
            </Form.Item>
            
            <Form.Item
              name="isDefault"
              valuePropName="checked"
            >
              <Checkbox>设为默认账户</Checkbox>
            </Form.Item>
            
            <Form.Item>
              <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
                <Button 
                  onClick={() => {
                    setEditModalVisible(false)
                    editForm.resetFields()
                  }}
                  size={isMobile ? 'middle' : 'large'}
                  style={isMobile ? { minHeight: '44px' } : undefined}
                >
                  取消
                </Button>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={editLoading}
                  size={isMobile ? 'middle' : 'large'}
                  style={isMobile ? { minHeight: '44px' } : undefined}
                >
                  保存
                </Button>
              </Space>
            </Form.Item>
          </Form>
        ) : (
          <div style={{ textAlign: 'center', padding: '20px' }}>
            <Spin size="large" />
            <div style={{ marginTop: '16px' }}>加载中...</div>
          </div>
        )}
      </Modal>
    </div>
  )
}

export default AccountDetail

