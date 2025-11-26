import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Typography, Spin, Modal, Descriptions, Divider, Form, Input, Checkbox, Alert } from 'antd'
import { PlusOutlined, StarOutlined, StarFilled, ReloadOutlined, EditOutlined } from '@ant-design/icons'
import { useAccountStore } from '../store/accountStore'
import type { Account } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const AccountList: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, loading, fetchAccounts, deleteAccount, setDefaultAccount, fetchAccountBalance, fetchAccountDetail, updateAccount } = useAccountStore()
  const [balanceMap, setBalanceMap] = useState<Record<number, { total: string; available: string; position: string }>>({})
  const [balanceLoading, setBalanceLoading] = useState<Record<number, boolean>>({})
  const [detailModalVisible, setDetailModalVisible] = useState(false)
  const [detailAccount, setDetailAccount] = useState<Account | null>(null)
  const [detailBalance, setDetailBalance] = useState<{ total: string; available: string; position: string; positions: any[] } | null>(null)
  const [detailBalanceLoading, setDetailBalanceLoading] = useState(false)
  const [editModalVisible, setEditModalVisible] = useState(false)
  const [editAccount, setEditAccount] = useState<Account | null>(null)
  const [editForm] = Form.useForm()
  const [editLoading, setEditLoading] = useState(false)
  
  useEffect(() => {
    fetchAccounts()
  }, [fetchAccounts])
  
  // 加载所有账户的余额
  useEffect(() => {
    const loadBalances = async () => {
      for (const account of accounts) {
        if (!balanceMap[account.id] && !balanceLoading[account.id]) {
          setBalanceLoading(prev => ({ ...prev, [account.id]: true }))
          try {
            const balanceData = await fetchAccountBalance(account.id)
            setBalanceMap(prev => ({ 
              ...prev, 
              [account.id]: {
                total: balanceData.totalBalance || '0',
                available: balanceData.availableBalance || '0',
                position: balanceData.positionBalance || '0'
              }
            }))
          } catch (error) {
            console.error(`获取账户 ${account.id} 余额失败:`, error)
            setBalanceMap(prev => ({ 
              ...prev, 
              [account.id]: { total: '-', available: '-', position: '-' }
            }))
          } finally {
            setBalanceLoading(prev => ({ ...prev, [account.id]: false }))
          }
        }
      }
    }
    
    if (accounts.length > 0) {
      loadBalances()
    }
  }, [accounts])
  
  const handleDelete = async (account: Account) => {
    try {
      await deleteAccount(account.id)
      message.success('删除账户成功')
    } catch (error: any) {
      message.error(error.message || '删除账户失败')
    }
  }
  
  const handleSetDefault = async (account: Account) => {
    try {
      await setDefaultAccount(account.id)
      message.success('设置默认账户成功')
    } catch (error: any) {
      message.error(error.message || '设置默认账户失败')
    }
  }
  
  const handleShowDetail = async (account: Account) => {
    try {
      setDetailModalVisible(true)
      setDetailAccount(account)
      setDetailBalance(null)
      setDetailBalanceLoading(false)
      
      // 加载详情和余额
      try {
        const accountDetail = await fetchAccountDetail(account.id)
        setDetailAccount(accountDetail)
        
        // 加载余额
        setDetailBalanceLoading(true)
        try {
          const balanceData = await fetchAccountBalance(account.id)
          setDetailBalance({
            total: balanceData.totalBalance || '0',
            available: balanceData.availableBalance || '0',
            position: balanceData.positionBalance || '0',
            positions: balanceData.positions || []
          })
        } catch (error) {
          console.error('获取余额失败:', error)
          setDetailBalance(null)
        } finally {
          setDetailBalanceLoading(false)
        }
      } catch (error: any) {
        console.error('获取账户详情失败:', error)
        message.error(error.message || '获取账户详情失败')
        setDetailModalVisible(false)
        setDetailAccount(null)
      }
    } catch (error: any) {
      console.error('打开详情失败:', error)
      message.error('打开详情失败')
      setDetailModalVisible(false)
      setDetailAccount(null)
    }
  }
  
  const handleRefreshDetailBalance = async () => {
    if (!detailAccount) return
    
    setDetailBalanceLoading(true)
    try {
      const balanceData = await fetchAccountBalance(detailAccount.id)
      setDetailBalance({
        total: balanceData.totalBalance || '0',
        available: balanceData.availableBalance || '0',
        position: balanceData.positionBalance || '0',
        positions: balanceData.positions || []
      })
      message.success('余额刷新成功')
    } catch (error: any) {
      message.error(error.message || '刷新余额失败')
    } finally {
      setDetailBalanceLoading(false)
    }
  }
  
  const handleShowEdit = async (account: Account) => {
    try {
      setEditModalVisible(true)
      setEditAccount(account)
      
      // 加载账户详情并设置表单初始值
      const accountDetail = await fetchAccountDetail(account.id)
      setEditAccount(accountDetail)
      
      editForm.setFieldsValue({
        accountName: accountDetail.accountName || '',
        apiKey: '',  // 不显示实际值，留空表示不修改
        apiSecret: '',  // 不显示实际值，留空表示不修改
        apiPassphrase: '',  // 不显示实际值，留空表示不修改
        isDefault: accountDetail.isDefault || false
      })
    } catch (error: any) {
      console.error('打开编辑失败:', error)
      message.error(error.message || '获取账户详情失败')
      setEditModalVisible(false)
      setEditAccount(null)
    }
  }
  
  const handleEditSubmit = async (values: any) => {
    if (!editAccount) return
    
    setEditLoading(true)
    try {
      // 构建更新请求，空字符串转换为 undefined（不修改）
      const updateData: any = {
        accountId: editAccount.id,
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
      setEditAccount(null)
      editForm.resetFields()
      
      // 刷新账户列表
      await fetchAccounts()
      
      // 如果详情 Modal 打开着，也刷新详情
      if (detailModalVisible && detailAccount && detailAccount.id === editAccount.id) {
        const accountDetail = await fetchAccountDetail(editAccount.id)
        setDetailAccount(accountDetail)
      }
    } catch (error: any) {
      message.error(error.message || '更新账户失败')
    } finally {
      setEditLoading(false)
    }
  }
  
  const columns = [
    {
      title: '账户名称',
      dataIndex: 'accountName',
      key: 'accountName',
      render: (text: string, record: Account) => text || `账户 ${record.id}`
    },
    {
      title: '钱包地址',
      dataIndex: 'walletAddress',
      key: 'walletAddress',
      render: (address: string) => (
        <span style={{ fontFamily: 'monospace' }}>{address}</span>
      )
    },
    {
      title: '默认账户',
      dataIndex: 'isDefault',
      key: 'isDefault',
      render: (isDefault: boolean, record: Account) => (
        <Button
          type="text"
          icon={isDefault ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
          onClick={() => !isDefault && handleSetDefault(record)}
          disabled={isDefault}
        />
      )
    },
    {
      title: 'API 凭证',
      key: 'apiCredentials',
      render: (_: any, record: Account) => {
        const allConfigured = record.apiKeyConfigured && record.apiSecretConfigured && record.apiPassphraseConfigured
        const partialConfigured = record.apiKeyConfigured || record.apiSecretConfigured || record.apiPassphraseConfigured
        return (
          <Tag color={allConfigured ? 'success' : partialConfigured ? 'warning' : 'default'}>
            {allConfigured ? '完整配置' : partialConfigured ? '部分配置' : '未配置'}
          </Tag>
        )
      }
    },
    {
      title: '余额',
      dataIndex: 'balance',
      key: 'balance',
      render: (_: any, record: Account) => {
        if (balanceLoading[record.id]) {
          return <Spin size="small" />
        }
        const balanceObj = balanceMap[record.id]
        const balance = balanceObj?.total || record.balance || '-'
        return balance && balance !== '-' && typeof balance === 'string' ? `${balance} USDC` : '-'
      }
    },
    {
      title: '活跃订单',
      dataIndex: 'activeOrders',
      key: 'activeOrders',
      render: (_: any, record: Account) => {
        if (record.activeOrders !== undefined && record.activeOrders !== null) {
          return <Tag color={record.activeOrders > 0 ? 'orange' : 'default'}>{record.activeOrders}</Tag>
        }
        return <span style={{ color: '#999' }}>-</span>
      }
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: Account) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => handleShowDetail(record)}
          >
            详情
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleShowEdit(record)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定要删除这个账户吗？"
            description={
              record.apiKeyConfigured 
                ? "删除账户前，请确保已取消所有活跃订单。删除后无法恢复，请谨慎操作！"
                : "删除后无法恢复，请谨慎操作！"
            }
            onConfirm={() => handleDelete(record)}
            okText="确定删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  const mobileColumns = [
    {
      title: '账户信息',
      key: 'info',
      render: (_: any, record: Account) => {
        const allConfigured = record.apiKeyConfigured && record.apiSecretConfigured && record.apiPassphraseConfigured
        const partialConfigured = record.apiKeyConfigured || record.apiSecretConfigured || record.apiPassphraseConfigured
        
        return (
          <div style={{ padding: '8px 0' }}>
            <div style={{ 
              fontWeight: 'bold', 
              marginBottom: '8px',
              fontSize: '16px'
            }}>
              {record.accountName || `账户 ${record.id}`}
            </div>
            <div style={{ 
              fontSize: '11px', 
              color: '#666', 
              marginBottom: '8px',
              wordBreak: 'break-all',
              fontFamily: 'monospace',
              lineHeight: '1.4'
            }}>
              {record.walletAddress}
            </div>
            <div style={{ marginBottom: '8px', display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
              <Tag color={record.isDefault ? 'gold' : 'default'} style={{ margin: 0 }}>
                {record.isDefault ? '默认' : '普通'}
              </Tag>
              <Tag color={allConfigured ? 'success' : partialConfigured ? 'warning' : 'default'} style={{ margin: 0 }}>
                {allConfigured ? '完整配置' : partialConfigured ? '部分配置' : '未配置'}
              </Tag>
            </div>
            <div style={{ 
              fontSize: '14px',
              fontWeight: '500',
              color: '#1890ff'
            }}>
              总余额: {balanceLoading[record.id] ? (
                <Spin size="small" style={{ marginLeft: '4px' }} />
              ) : balanceMap[record.id]?.total && balanceMap[record.id].total !== '-' ? (
                `${balanceMap[record.id].total} USDC`
              ) : (
                '-'
              )}
            </div>
            {balanceMap[record.id] && balanceMap[record.id].available !== '-' && (
              <div style={{ 
                fontSize: '12px',
                color: '#666',
                marginTop: '4px'
              }}>
                可用: {balanceMap[record.id].available} USDC | 仓位: {balanceMap[record.id].position} USDC
              </div>
            )}
            {(record.activeOrders !== undefined && record.activeOrders !== null) && (
              <div style={{ 
                fontSize: '12px',
                color: '#666',
                marginTop: '4px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
              }}>
                活跃订单: <Tag color={record.activeOrders > 0 ? 'orange' : 'default'} style={{ margin: 0 }}>{record.activeOrders}</Tag>
              </div>
            )}
          </div>
        )
      }
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_: any, record: Account) => (
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <Button
            type="primary"
            size="small"
            block
            onClick={() => handleShowDetail(record)}
            style={{ minHeight: '32px' }}
          >
            查看详情
          </Button>
          <Button
            size="small"
            block
            icon={<EditOutlined />}
            onClick={() => handleShowEdit(record)}
            style={{ minHeight: '32px' }}
          >
            编辑
          </Button>
          {!record.isDefault && (
            <Button
              size="small"
              block
              icon={<StarOutlined />}
              onClick={() => handleSetDefault(record)}
              style={{ minHeight: '32px' }}
            >
              设为默认
            </Button>
          )}
          <Popconfirm
            title="确定要删除这个账户吗？"
            description={
              record.apiKeyConfigured 
                ? "删除账户前，请确保已取消所有活跃订单。删除后无法恢复，请谨慎操作！"
                : "删除后无法恢复，请谨慎操作！"
            }
            onConfirm={() => handleDelete(record)}
            okText="确定删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
          >
            <Button 
              size="small" 
              block 
              danger
              style={{ minHeight: '32px' }}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
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
        <Title level={isMobile ? 3 : 2} style={{ margin: 0, fontSize: isMobile ? '18px' : undefined }}>
          账户管理
        </Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate('/accounts/import')}
          size={isMobile ? 'middle' : 'large'}
          block={isMobile}
          style={isMobile ? { minHeight: '44px' } : undefined}
        >
          导入账户
        </Button>
      </div>
      
      <Card style={{ 
        margin: isMobile ? '0 -8px' : '0',
        borderRadius: isMobile ? '0' : undefined
      }}>
        {isMobile ? (
          <Table
            dataSource={accounts}
            columns={mobileColumns}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 10,
              showSizeChanger: false,
              simple: true,
              size: 'small'
            }}
            scroll={{ x: 'max-content' }}
            size="small"
            style={{ fontSize: '14px' }}
          />
        ) : (
          <Table
            dataSource={accounts}
            columns={columns}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true
            }}
          />
        )}
      </Card>
      
      {/* 账户详情 Modal */}
      <Modal
        title={detailAccount ? (detailAccount.accountName || `账户 ${detailAccount.id}`) : '账户详情'}
        open={detailModalVisible}
        onCancel={() => {
          setDetailModalVisible(false)
          setDetailAccount(null)
          setDetailBalance(null)
        }}
        footer={[
          <Button 
            key="refresh" 
            icon={<ReloadOutlined />} 
            onClick={handleRefreshDetailBalance} 
            loading={detailBalanceLoading}
            disabled={!detailAccount}
          >
            刷新余额
          </Button>,
          <Button 
            key="edit" 
            type="primary"
            icon={<EditOutlined />} 
            onClick={() => {
              if (detailAccount) {
                setDetailModalVisible(false)
                handleShowEdit(detailAccount)
              }
            }}
            disabled={!detailAccount}
          >
            编辑
          </Button>,
          <Button 
            key="close" 
            onClick={() => {
              setDetailModalVisible(false)
              setDetailAccount(null)
              setDetailBalance(null)
            }}
          >
            关闭
          </Button>
        ]}
        width={isMobile ? '95%' : 800}
        style={{ top: isMobile ? 20 : 50 }}
        destroyOnClose
        maskClosable
        closable
      >
        {detailAccount ? (
          <div>
            <Descriptions
              column={isMobile ? 1 : 2}
              bordered
              size={isMobile ? 'small' : 'middle'}
            >
              <Descriptions.Item label="账户ID">
                {detailAccount.id}
              </Descriptions.Item>
              <Descriptions.Item label="账户名称">
                {detailAccount.accountName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="钱包地址" span={isMobile ? 1 : 2}>
                <span style={{ 
                  fontFamily: 'monospace', 
                  fontSize: isMobile ? '11px' : '13px',
                  wordBreak: 'break-all',
                  lineHeight: '1.4',
                  display: 'block'
                }}>
                  {detailAccount.walletAddress || '-'}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="默认账户">
                <Tag color={detailAccount.isDefault ? 'gold' : 'default'}>
                  {detailAccount.isDefault ? '是' : '否'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="总余额" span={isMobile ? 1 : 2}>
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ fontWeight: 'bold', color: '#1890ff', fontSize: '16px' }}>
                    {detailBalance.total} USDC
                  </span>
                ) : (
                  <span style={{ color: '#999' }}>-</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="可用余额">
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ color: '#52c41a' }}>
                    {detailBalance.available} USDC
                  </span>
                ) : (
                  <span style={{ color: '#999' }}>-</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="仓位余额">
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ color: '#1890ff' }}>
                    {detailBalance.position} USDC
                  </span>
                ) : (
                  <span style={{ color: '#999' }}>-</span>
                )}
              </Descriptions.Item>
            </Descriptions>
            
            <Divider />
            
            <Descriptions
              column={isMobile ? 1 : 2}
              bordered
              size={isMobile ? 'small' : 'middle'}
              title="API 凭证配置"
            >
              <Descriptions.Item label="API Key">
                <Tag color={detailAccount.apiKeyConfigured ? 'success' : 'default'}>
                  {detailAccount.apiKeyConfigured ? '已配置' : '未配置'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="API Secret">
                <Tag color={detailAccount.apiSecretConfigured ? 'success' : 'default'}>
                  {detailAccount.apiSecretConfigured ? '已配置' : '未配置'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="API Passphrase">
                <Tag color={detailAccount.apiPassphraseConfigured ? 'success' : 'default'}>
                  {detailAccount.apiPassphraseConfigured ? '已配置' : '未配置'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="配置状态">
                {detailAccount.apiKeyConfigured && detailAccount.apiSecretConfigured && detailAccount.apiPassphraseConfigured ? (
                  <Tag color="success">完整配置</Tag>
                ) : (
                  <Tag color="warning">部分配置</Tag>
                )}
              </Descriptions.Item>
            </Descriptions>
            
            {(detailAccount.totalOrders !== undefined || detailAccount.totalPnl !== undefined || 
              detailAccount.activeOrders !== undefined || 
              detailAccount.completedOrders !== undefined || detailAccount.positionCount !== undefined) && (
              <>
                <Divider />
                <Descriptions
                  column={isMobile ? 1 : 2}
                  bordered
                  size={isMobile ? 'small' : 'middle'}
                  title="交易统计"
                >
                  {detailAccount.totalOrders !== undefined && (
                    <Descriptions.Item label="总订单数">
                      {detailAccount.totalOrders}
                    </Descriptions.Item>
                  )}
                  {detailAccount.activeOrders !== undefined && (
                    <Descriptions.Item label="活跃订单数">
                      <Tag color={detailAccount.activeOrders > 0 ? 'orange' : 'default'}>{detailAccount.activeOrders}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.completedOrders !== undefined && (
                    <Descriptions.Item label="已完成订单数">
                      <Tag color="success">{detailAccount.completedOrders}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.positionCount !== undefined && (
                    <Descriptions.Item label="持仓数量">
                      <Tag color={detailAccount.positionCount > 0 ? 'blue' : 'default'}>{detailAccount.positionCount}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.totalPnl !== undefined && (
                    <Descriptions.Item label="总盈亏">
                      <span style={{ 
                        fontWeight: 'bold',
                        color: detailAccount.totalPnl && detailAccount.totalPnl.startsWith('-') ? '#ff4d4f' : '#52c41a'
                      }}>
                        {detailAccount.totalPnl} USDC
                      </span>
                    </Descriptions.Item>
                  )}
                </Descriptions>
              </>
            )}
          </div>
        ) : (
          <div style={{ textAlign: 'center', padding: '20px' }}>
            <Spin size="large" />
            <div style={{ marginTop: '16px' }}>加载中...</div>
          </div>
        )}
      </Modal>
      
      {/* 编辑账户 Modal */}
      <Modal
        title={editAccount ? `编辑账户 - ${editAccount.accountName || `账户 ${editAccount.id}`}` : '编辑账户'}
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false)
          setEditAccount(null)
          editForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        destroyOnClose
        maskClosable
        closable
      >
        {editAccount ? (
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
                    setEditAccount(null)
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

export default AccountList

