import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Descriptions, Button, Space, Tag, Spin, message, Typography, Divider, Modal, Form, Input, Alert } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, EditOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useAccountStore } from '../store/accountStore'
import type { Account } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Title } = Typography

const AccountDetail: React.FC = () => {
  const { t } = useTranslation()
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
      message.error(t('account.accountIdRequired'))
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
      message.error(error.message || t('account.getDetailFailed'))
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
      // 构建更新请求，只支持编辑账户名称
      const updateData: any = {
        accountId: account.id,
        accountName: values.accountName || undefined,
      }
      
      await updateAccount(updateData)
      
      message.success(t('account.updateSuccess'))
      setEditModalVisible(false)
      editForm.resetFields()
      
      // 刷新账户详情
      if (accountId) {
        await loadAccountDetail()
      }
    } catch (error: any) {
      message.error(error.message || t('account.updateFailed'))
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
            {t('common.back')}
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
            {t('account.refreshBalance')}
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
                apiPassphrase: ''  // 不显示实际值，留空表示不修改
              })
            }}
            size={isMobile ? 'middle' : 'large'}
            block={isMobile}
            style={isMobile ? { minHeight: '44px' } : undefined}
          >
            {t('common.edit')}
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
          <Descriptions.Item label={t('account.accountId')}>
            {account.id}
          </Descriptions.Item>
          <Descriptions.Item label={t('account.accountName')}>
            {account.accountName || '-'}
          </Descriptions.Item>
          {account.walletType && (
            <Descriptions.Item label={t('account.walletType')}>
              <Tag color={account.walletType.toLowerCase() === 'magic' ? 'purple' : 'blue'}>
                {account.walletType.toLowerCase() === 'magic' ? 'Magic' : 'Safe'}
              </Tag>
            </Descriptions.Item>
          )}
          <Descriptions.Item label={t('account.walletAddress')} span={isMobile ? 1 : 2}>
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
          <Descriptions.Item label={t('account.balance')}>
            {balanceLoading ? (
              <Spin size="small" />
            ) : balance ? (
              <span style={{ fontWeight: 'bold', color: '#1890ff' }}>
                {formatUSDC(balance)} USDC
              </span>
            ) : (
              <span style={{ color: '#999' }}>-</span>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      
      <Divider />
      
      <Card 
        title={t('account.apiCredentials')} 
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
          <Descriptions.Item label={t('account.apiKey')}>
            <Tag color={account.apiKeyConfigured ? 'success' : 'default'}>
              {account.apiKeyConfigured ? t('account.configured') : t('account.notConfigured')}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('account.apiSecret')}>
            <Tag color={account.apiSecretConfigured ? 'success' : 'default'}>
              {account.apiSecretConfigured ? t('account.configured') : t('account.notConfigured')}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('account.apiPassphrase')}>
            <Tag color={account.apiPassphraseConfigured ? 'success' : 'default'}>
              {account.apiPassphraseConfigured ? t('account.configured') : t('account.notConfigured')}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('account.apiCredentials')}>
            {account.apiKeyConfigured && account.apiSecretConfigured && account.apiPassphraseConfigured ? (
              <Tag color="success">{t('account.fullConfig')}</Tag>
            ) : (
              <Tag color="warning">{t('account.partialConfig')}</Tag>
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
            title={t('account.statistics')} 
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
                <Descriptions.Item label={t('account.totalOrders')}>
                  {account.totalOrders}
                </Descriptions.Item>
              )}
              {account.activeOrders !== undefined && (
                <Descriptions.Item label={t('account.activeOrders')}>
                  <Tag color={account.activeOrders > 0 ? 'orange' : 'default'}>{account.activeOrders}</Tag>
                </Descriptions.Item>
              )}
              {account.completedOrders !== undefined && (
                <Descriptions.Item label={t('account.completedOrders')}>
                  <Tag color="success">{account.completedOrders}</Tag>
                </Descriptions.Item>
              )}
              {account.positionCount !== undefined && (
                <Descriptions.Item label={t('account.positionCount')}>
                  <Tag color={account.positionCount > 0 ? 'blue' : 'default'}>{account.positionCount}</Tag>
                </Descriptions.Item>
              )}
              {account.totalPnl !== undefined && (
                <Descriptions.Item label={t('account.totalPnl')}>
                  <span style={{ 
                    fontWeight: 'bold',
                    color: account.totalPnl.startsWith('-') ? '#ff4d4f' : '#52c41a'
                  }}>
                    {formatUSDC(account.totalPnl)} USDC
                  </span>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        </>
      ) : null}
      
      {/* 编辑账户 Modal */}
      <Modal
        title={account ? `${t('common.edit')} ${t('account.title')} - ${account.accountName || `${t('account.title')} ${account.id}`}` : t('common.edit') + ' ' + t('account.title')}
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
              message={t('account.editTip') || '编辑账户'}
              description={t('account.editTipDesc') || '只能编辑账户名称，API 凭证需要通过导入账户功能更新。'}
              type="info"
              showIcon
              style={{ marginBottom: '24px' }}
            />
            
            <Form.Item
              label={t('account.accountName') || '账户名称'}
              name="accountName"
            >
              <Input placeholder={t('account.accountNamePlaceholder') || '请输入账户名称（可选）'} />
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
                  {t('common.cancel')}
                </Button>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={editLoading}
                  size={isMobile ? 'middle' : 'large'}
                  style={isMobile ? { minHeight: '44px' } : undefined}
                >
                  {t('common.save')}
                </Button>
              </Space>
            </Form.Item>
          </Form>
        ) : (
          <div style={{ textAlign: 'center', padding: '20px' }}>
            <Spin size="large" />
            <div style={{ marginTop: '16px' }}>{t('common.loading')}</div>
          </div>
        )}
      </Modal>
    </div>
  )
}

export default AccountDetail





