import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Typography, Spin, Modal, Descriptions, Divider, Form, Input, Alert } from 'antd'
import { PlusOutlined, ReloadOutlined, EditOutlined, CopyOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useAccountStore } from '../store/accountStore'
import type { Account } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'
import AccountImportForm from '../components/AccountImportForm'

const { Title } = Typography

const AccountList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, loading, fetchAccounts, deleteAccount, fetchAccountBalance, fetchAccountDetail, updateAccount } = useAccountStore()
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
  const [accountImportModalVisible, setAccountImportModalVisible] = useState(false)
  const [accountImportForm] = Form.useForm()
  
  useEffect(() => {
    fetchAccounts()
  }, [fetchAccounts])
  
  const handleAccountImportSuccess = async () => {
    message.success(t('accountImport.importSuccess'))
    setAccountImportModalVisible(false)
    accountImportForm.resetFields()
    fetchAccounts()
  }
  
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
      message.success(t('accountList.deleteSuccess'))
    } catch (error: any) {
      message.error(error.message || t('accountList.deleteFailed'))
    }
  }
  
  const handleCopy = (text: string) => {
    if (!text) {
      message.warning(t('accountList.copyFailed') || '复制失败：地址为空')
      return
    }
    
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        message.success({
          content: t('accountList.copySuccess') || '已复制到剪贴板',
          duration: 2
        })
      }).catch((err) => {
        console.error('复制失败:', err)
        // 降级方案：使用传统方法
        fallbackCopyTextToClipboard(text)
      })
    } else {
      // 降级方案：使用传统方法
      fallbackCopyTextToClipboard(text)
    }
  }
  
  const fallbackCopyTextToClipboard = (text: string) => {
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()
    
    try {
      const successful = document.execCommand('copy')
      if (successful) {
        message.success({
          content: t('accountList.copySuccess') || '已复制到剪贴板',
          duration: 2
        })
      } else {
        message.error(t('accountList.copyFailed') || '复制失败')
      }
    } catch (err) {
      console.error('复制失败:', err)
      message.error(t('accountList.copyFailed') || '复制失败')
    } finally {
      document.body.removeChild(textArea)
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
        message.error(error.message || t('accountList.getDetailFailed'))
        setDetailModalVisible(false)
        setDetailAccount(null)
      }
    } catch (error: any) {
      console.error('打开详情失败:', error)
      message.error(t('accountList.openDetailFailed'))
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
      message.success(t('accountList.refreshBalanceSuccess'))
    } catch (error: any) {
      message.error(error.message || t('accountList.refreshBalanceFailed'))
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
        apiPassphrase: ''  // 不显示实际值，留空表示不修改
      })
    } catch (error: any) {
      console.error('打开编辑失败:', error)
      message.error(error.message || t('accountList.getDetailFailedForEdit'))
      setEditModalVisible(false)
      setEditAccount(null)
    }
  }
  
  const handleEditSubmit = async (values: any) => {
    if (!editAccount) return
    
    setEditLoading(true)
    try {
      // 构建更新请求，只支持编辑账户名称
      const updateData: any = {
        accountId: editAccount.id,
        accountName: values.accountName || undefined
      }
      
      await updateAccount(updateData)
      
      message.success(t('accountList.updateSuccess'))
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
      message.error(error.message || t('accountList.updateFailed'))
    } finally {
      setEditLoading(false)
    }
  }
  
  const columns = [
    {
      title: t('accountList.accountName'),
      dataIndex: 'accountName',
      key: 'accountName',
      render: (text: string, record: Account) => text || `${t('accountList.accountName')} ${record.id}`
    },
    {
      title: t('accountList.walletAddress'),
      dataIndex: 'walletAddress',
      key: 'walletAddress',
      render: (text: string) => {
        const formatted = text ? `${text.slice(0, 6)}...${text.slice(-4)}` : '-'
        return (
          <Space>
            <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>{formatted}</span>
            <Button
              type="text"
              size="small"
              icon={<CopyOutlined />}
              onClick={(e) => {
                e.stopPropagation()
                handleCopy(text)
              }}
              title={t('accountList.walletAddress')}
            />
          </Space>
        )
      }
    },
    {
      title: t('accountList.proxyAddress'),
      dataIndex: 'proxyAddress',
      key: 'proxyAddress',
      render: (address: string) => {
        const formatted = address ? `${address.slice(0, 6)}...${address.slice(-4)}` : '-'
        return (
          <Space>
            <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>{formatted}</span>
            <Button
              type="text"
              size="small"
              icon={<CopyOutlined />}
              onClick={(e) => {
                e.stopPropagation()
                handleCopy(address)
              }}
              title={t('accountList.proxyAddress')}
            />
          </Space>
        )
      }
    },
    {
      title: t('accountList.balance'),
      dataIndex: 'balance',
      key: 'balance',
      render: (_: any, record: Account) => {
        if (balanceLoading[record.id]) {
          return <Spin size="small" />
        }
        const balanceObj = balanceMap[record.id]
        const balance = balanceObj?.total || record.balance || '-'
        return balance && balance !== '-' && typeof balance === 'string' ? `${formatUSDC(balance)} USDC` : '-'
      }
    },
    {
      title: t('accountList.action'),
      key: 'action',
      render: (_: any, record: Account) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => handleShowDetail(record)}
          >
            {t('accountList.detail')}
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleShowEdit(record)}
          >
            {t('accountList.edit')}
          </Button>
          <Popconfirm
            title={t('accountList.deleteConfirm')}
            description={
              record.apiKeyConfigured 
                ? t('accountList.deleteConfirmDesc')
                : t('accountList.deleteConfirmDescSimple')
            }
            onConfirm={() => handleDelete(record)}
            okText={t('accountList.deleteConfirmOk')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger>
              {t('accountList.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  const mobileColumns = [
    {
      title: t('accountList.accountName'),
      key: 'info',
      render: (_: any, record: Account) => {
        return (
          <div style={{ padding: '8px 0' }}>
            <div style={{ 
              fontWeight: 'bold', 
              marginBottom: '8px',
              fontSize: '16px'
            }}>
              {record.accountName || `${t('accountList.accountName')} ${record.id}`}
            </div>
            <div style={{ 
              fontSize: '11px', 
              color: '#666', 
              marginBottom: '8px',
              wordBreak: 'break-all',
              fontFamily: 'monospace',
              lineHeight: '1.4'
            }}>
              <div style={{ marginBottom: '4px' }}>
                <strong>{t('accountList.walletAddress')}:</strong> {record.walletAddress ? `${record.walletAddress.slice(0, 6)}...${record.walletAddress.slice(-4)}` : '-'}
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={(e) => {
                    e.stopPropagation()
                    handleCopy(record.walletAddress)
                  }}
                  style={{ marginLeft: '4px', padding: '0 4px' }}
                />
              </div>
              <div>
                <strong>{t('accountList.proxyAddress')}:</strong> {record.proxyAddress ? `${record.proxyAddress.slice(0, 6)}...${record.proxyAddress.slice(-4)}` : '-'}
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={(e) => {
                    e.stopPropagation()
                    handleCopy(record.proxyAddress)
                  }}
                  style={{ marginLeft: '4px', padding: '0 4px' }}
                />
              </div>
            </div>
            <div style={{ 
              fontSize: '14px',
              fontWeight: '500',
              color: '#1890ff'
            }}>
              {t('accountList.totalBalance')}: {balanceLoading[record.id] ? (
                <Spin size="small" style={{ marginLeft: '4px' }} />
              ) : balanceMap[record.id]?.total && balanceMap[record.id].total !== '-' ? (
                `${formatUSDC(balanceMap[record.id].total)} USDC`
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
                {t('accountList.available')}: {formatUSDC(balanceMap[record.id].available)} USDC | {t('accountList.position')}: {formatUSDC(balanceMap[record.id].position)} USDC
              </div>
            )}
          </div>
        )
      }
    },
    {
      title: t('accountList.action'),
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
            {t('accountList.viewDetail')}
          </Button>
          <Button
            size="small"
            block
            icon={<EditOutlined />}
            onClick={() => handleShowEdit(record)}
            style={{ minHeight: '32px' }}
          >
            {t('accountList.edit')}
          </Button>
          <Popconfirm
            title={t('accountList.deleteConfirm')}
            description={
              record.apiKeyConfigured 
                ? t('accountList.deleteConfirmDesc')
                : t('accountList.deleteConfirmDescSimple')
            }
            onConfirm={() => handleDelete(record)}
            okText={t('accountList.deleteConfirmOk')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
          >
            <Button 
              size="small" 
              block 
              danger
              style={{ minHeight: '32px' }}
            >
              {t('accountList.delete')}
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
          {t('accountList.title')}
        </Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setAccountImportModalVisible(true)}
          size={isMobile ? 'middle' : 'large'}
          block={isMobile}
          style={isMobile ? { minHeight: '44px' } : undefined}
        >
          {t('accountList.importAccount')}
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
        title={detailAccount ? (detailAccount.accountName || `${t('accountList.accountName')} ${detailAccount.id}`) : t('accountList.accountDetail')}
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
            {t('accountList.refreshBalance')}
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
            {t('accountList.edit')}
          </Button>,
          <Button 
            key="close" 
            onClick={() => {
              setDetailModalVisible(false)
              setDetailAccount(null)
              setDetailBalance(null)
            }}
          >
            {t('common.close')}
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
              <Descriptions.Item label={t('accountList.accountId')}>
                {detailAccount.id}
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.accountName')}>
                {detailAccount.accountName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.walletAddress')} span={isMobile ? 1 : 2}>
                <Space>
                  <span style={{ 
                    fontFamily: 'monospace', 
                    fontSize: isMobile ? '11px' : '13px',
                    wordBreak: 'break-all',
                    lineHeight: '1.4',
                    display: 'block'
                  }}>
                    {detailAccount.walletAddress || '-'}
                  </span>
                  <Button
                    type="text"
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={(e) => {
                      e.stopPropagation()
                      handleCopy(detailAccount.walletAddress || '')
                    }}
                    title={t('accountList.walletAddress')}
                  />
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.proxyAddress')} span={isMobile ? 1 : 2}>
                <Space>
                  <span style={{ 
                    fontFamily: 'monospace', 
                    fontSize: isMobile ? '11px' : '13px',
                    wordBreak: 'break-all',
                    lineHeight: '1.4',
                    display: 'block'
                  }}>
                    {detailAccount.proxyAddress || '-'}
                  </span>
                  <Button
                    type="text"
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={(e) => {
                      e.stopPropagation()
                      handleCopy(detailAccount.proxyAddress || '')
                    }}
                    title={t('accountList.proxyAddress')}
                  />
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.totalBalance')} span={isMobile ? 1 : 2}>
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ fontWeight: 'bold', color: '#1890ff', fontSize: '16px' }}>
                    {formatUSDC(detailBalance.total)} USDC
                  </span>
                ) : (
                  <span style={{ color: '#999' }}>-</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.available')}>
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ color: '#52c41a' }}>
                    {formatUSDC(detailBalance.available)} USDC
                  </span>
                ) : (
                  <span style={{ color: '#999' }}>-</span>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.position')}>
                {detailBalanceLoading ? (
                  <Spin size="small" />
                ) : detailBalance ? (
                  <span style={{ color: '#1890ff' }}>
                    {formatUSDC(detailBalance.position)} USDC
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
              title={t('accountList.apiCredentials')}
            >
              <Descriptions.Item label={t('accountList.apiKey')}>
                <Tag color={detailAccount.apiKeyConfigured ? 'success' : 'default'}>
                  {detailAccount.apiKeyConfigured ? t('accountList.configured') : t('accountList.notConfiguredStatus')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.apiSecret')}>
                <Tag color={detailAccount.apiSecretConfigured ? 'success' : 'default'}>
                  {detailAccount.apiSecretConfigured ? t('accountList.configured') : t('accountList.notConfiguredStatus')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.apiPassphrase')}>
                <Tag color={detailAccount.apiPassphraseConfigured ? 'success' : 'default'}>
                  {detailAccount.apiPassphraseConfigured ? t('accountList.configured') : t('accountList.notConfiguredStatus')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('accountList.configStatus')}>
                {detailAccount.apiKeyConfigured && detailAccount.apiSecretConfigured && detailAccount.apiPassphraseConfigured ? (
                  <Tag color="success">{t('accountList.fullConfig')}</Tag>
                ) : (
                  <Tag color="warning">{t('accountList.partialConfig')}</Tag>
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
                  title={t('accountList.statistics')}
                >
                  {detailAccount.totalOrders !== undefined && (
                    <Descriptions.Item label={t('accountList.totalOrders')}>
                      {detailAccount.totalOrders}
                    </Descriptions.Item>
                  )}
                  {detailAccount.activeOrders !== undefined && (
                    <Descriptions.Item label={t('accountList.activeOrdersCount')}>
                      <Tag color={detailAccount.activeOrders > 0 ? 'orange' : 'default'}>{detailAccount.activeOrders}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.completedOrders !== undefined && (
                    <Descriptions.Item label={t('accountList.completedOrders')}>
                      <Tag color="success">{detailAccount.completedOrders}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.positionCount !== undefined && (
                    <Descriptions.Item label={t('accountList.positionCount')}>
                      <Tag color={detailAccount.positionCount > 0 ? 'blue' : 'default'}>{detailAccount.positionCount}</Tag>
                    </Descriptions.Item>
                  )}
                  {detailAccount.totalPnl !== undefined && (
                    <Descriptions.Item label={t('accountList.totalPnl')}>
                      <span style={{ 
                        fontWeight: 'bold',
                        color: detailAccount.totalPnl && detailAccount.totalPnl.startsWith('-') ? '#ff4d4f' : '#52c41a'
                      }}>
                        {formatUSDC(detailAccount.totalPnl)} USDC
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
            <div style={{ marginTop: '16px' }}>{t('accountList.loading')}</div>
          </div>
        )}
      </Modal>
      
      {/* 编辑账户 Modal */}
      <Modal
        title={editAccount ? `${t('accountList.editAccount')} - ${editAccount.accountName || `${t('accountList.accountName')} ${editAccount.id}`}` : t('accountList.editAccount')}
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
              message={t('accountList.editTip') || '编辑账户'}
              description={t('accountList.editTipDesc') || '只能编辑账户名称，API 凭证需要通过导入账户功能更新。'}
              type="info"
              showIcon
              style={{ marginBottom: '24px' }}
            />
            
            <Form.Item
              label={t('accountList.accountName') || '账户名称'}
              name="accountName"
            >
              <Input placeholder={t('accountList.accountNamePlaceholder') || '请输入账户名称（可选）'} />
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
            <div style={{ marginTop: '16px' }}>{t('accountList.loading')}</div>
          </div>
        )}
      </Modal>
      
      {/* 导入账户 Modal */}
      <Modal
        title={t('accountImport.title')}
        open={accountImportModalVisible}
        onCancel={() => {
          setAccountImportModalVisible(false)
          accountImportForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 150px)', overflow: 'auto' }}
        destroyOnClose
        maskClosable
        closable
      >
        <AccountImportForm
          form={accountImportForm}
          onSuccess={handleAccountImportSuccess}
          onCancel={() => {
            setAccountImportModalVisible(false)
            accountImportForm.resetFields()
          }}
          showAlert={true}
          showCancelButton={true}
        />
      </Modal>
    </div>
  )
}

export default AccountList

