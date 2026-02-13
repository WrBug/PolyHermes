import { useState, useEffect } from 'react'
import { Form, Input, Button, Radio, Space, Card, Spin, message, Alert, Steps, Tag } from 'antd'
import { KeyOutlined, WalletOutlined, UserOutlined, CheckCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useAccountStore } from '../store/accountStore'
import {
  getAddressFromPrivateKey,
  getAddressFromMnemonic,
  getPrivateKeyFromMnemonic,
  isValidWalletAddress,
  isValidPrivateKey,
  isValidMnemonic,
  formatUSDC
} from '../utils'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import type { ProxyOption } from '../types'

type ImportType = 'privateKey' | 'mnemonic'

interface AccountImportFormProps {
  form: any
  onSuccess?: (accountId: number) => void
  onCancel?: () => void
  showCancelButton?: boolean
}

const AccountImportForm: React.FC<AccountImportFormProps> = ({
  form,
  onSuccess,
  onCancel,
  showCancelButton = true
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { importAccount, loading } = useAccountStore()
  const [importType, setImportType] = useState<ImportType>('privateKey')
  const [derivedAddress, setDerivedAddress] = useState<string>('')
  const [addressError, setAddressError] = useState<string>('')
  const [proxyOptions, setProxyOptions] = useState<ProxyOption[]>([])
  const [selectedProxyType, setSelectedProxyType] = useState<string>('')
  const [loadingProxyOptions, setLoadingProxyOptions] = useState<boolean>(false)
  const [step, setStep] = useState<'input' | 'select'>('input') // 步骤：输入 -> 选择代理地址
  
  // 当私钥输入时，自动推导地址（不支持换行，自动去除换行符）
  const handlePrivateKeyChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const raw = e.target.value
    const normalized = raw.replace(/\r?\n/g, '')
    if (normalized !== raw) {
      form.setFieldsValue({ privateKey: normalized })
    }
    const privateKey = normalized.trim()
    if (!privateKey) {
      setDerivedAddress('')
      setAddressError('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
      return
    }
    
    // 验证私钥格式
    if (!isValidPrivateKey(privateKey)) {
      setAddressError(t('accountImport.privateKeyInvalid'))
      setDerivedAddress('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
      return
    }
    
    try {
      const address = getAddressFromPrivateKey(privateKey)
      setDerivedAddress(address)
      setAddressError('')
      
      // 自动填充钱包地址字段
      form.setFieldsValue({ walletAddress: address })
      
      // 延迟获取代理选项（避免频繁请求）
      setTimeout(() => {
        fetchProxyOptions(address, privateKey, null)
      }, 500)
    } catch (error: any) {
      setAddressError(error.message || t('accountImport.addressError'))
      setDerivedAddress('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
    }
  }
  
  // 当助记词输入时，自动推导地址（不支持换行，换行符转为空格）
  const handleMnemonicChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const raw = e.target.value
    const normalized = raw.replace(/\r?\n/g, ' ').replace(/\s+/g, ' ').trimStart()
    if (/\r?\n/.test(raw)) {
      form.setFieldsValue({ mnemonic: normalized })
    }
    const mnemonic = normalized.trim()
    if (!mnemonic) {
      setDerivedAddress('')
      setAddressError('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
      return
    }
    
    // 验证助记词格式
    if (!isValidMnemonic(mnemonic)) {
      setAddressError(t('accountImport.mnemonicInvalid'))
      setDerivedAddress('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
      return
    }
    
    try {
      const address = getAddressFromMnemonic(mnemonic, 0)
      setDerivedAddress(address)
      setAddressError('')
      
      // 自动填充钱包地址字段
      form.setFieldsValue({ walletAddress: address })
      
      // 延迟获取代理选项（避免频繁请求）
      setTimeout(() => {
        fetchProxyOptions(address, null, mnemonic)
      }, 500)
    } catch (error: any) {
      setAddressError(error.message || t('accountImport.addressErrorMnemonic'))
      setDerivedAddress('')
      setProxyOptions([])
      setSelectedProxyType('')
      setStep('input')
    }
  }
  
  // 获取代理地址选项
  const fetchProxyOptions = async (walletAddress: string, privateKey: string | null, mnemonic: string | null) => {
    if (!walletAddress || (!privateKey && !mnemonic)) {
      return
    }
    
    setLoadingProxyOptions(true)
    try {
      const response = await apiService.accounts.checkProxyOptions({
        walletAddress,
        privateKey: privateKey || undefined,
        mnemonic: mnemonic || undefined
      })
      
      if (response.data.code === 0 && response.data.data) {
        const options = response.data.data.options || []
        setProxyOptions(options)
        
        // 如果有选项，进入选择步骤
        if (options.length > 0) {
          setStep('select')
          // 如果有资产，默认选择第一个有资产的选项
          const hasAssetsOption = options.find((opt: ProxyOption) => opt.hasAssets)
          if (hasAssetsOption) {
            setSelectedProxyType(hasAssetsOption.walletType)
          } else {
            // 否则选择第一个选项
            setSelectedProxyType(options[0].walletType)
          }
        } else {
          setStep('input')
          message.warning(t('accountImport.proxyOption.error') || '未获取到代理地址选项')
        }
      } else {
        setProxyOptions([])
        setStep('input')
        message.error(response.data.msg || '获取代理地址选项失败')
      }
    } catch (error: any) {
      setProxyOptions([])
      setStep('input')
      message.error(error.message || '获取代理地址选项失败')
    } finally {
      setLoadingProxyOptions(false)
    }
  }
  
  // 切换导入方式时重置状态
  useEffect(() => {
    setDerivedAddress('')
    setAddressError('')
    setProxyOptions([])
    setSelectedProxyType('')
    setStep('input')
    form.setFieldsValue({ walletAddress: '', privateKey: '', mnemonic: '' })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importType])
  
  const handleSubmit = async (values: any) => {
    try {
      // 如果还在输入步骤，需要先选择代理地址
      if (step === 'input' || !selectedProxyType) {
        return Promise.reject(new Error(t('accountImport.proxyOptionRequired')))
      }
      
      let privateKey: string
      let walletAddress: string
      
      if (importType === 'privateKey') {
        // 私钥模式
        privateKey = values.privateKey
        walletAddress = values.walletAddress
        
        // 验证推导的地址和输入的地址是否一致
        if (derivedAddress && walletAddress !== derivedAddress) {
          return Promise.reject(new Error(t('accountImport.walletAddressMismatch')))
        }
      } else {
        // 助记词模式
        if (!values.mnemonic) {
          return Promise.reject(new Error(t('accountImport.mnemonicRequired')))
        }
        
        // 从助记词导出私钥和地址
        privateKey = getPrivateKeyFromMnemonic(values.mnemonic, 0)
        const derivedAddressFromMnemonic = getAddressFromMnemonic(values.mnemonic, 0)
        
        // 如果用户手动输入了地址，验证是否与推导的地址一致
        if (values.walletAddress) {
          if (values.walletAddress !== derivedAddressFromMnemonic) {
            walletAddress = derivedAddressFromMnemonic
          } else {
            walletAddress = values.walletAddress
          }
        } else {
          walletAddress = derivedAddressFromMnemonic
        }
      }
      
      // 验证钱包地址格式
      if (!isValidWalletAddress(walletAddress)) {
        return Promise.reject(new Error(t('accountImport.walletAddressInvalid')))
      }
      
      await importAccount({
        privateKey: privateKey,
        walletAddress: walletAddress,
        accountName: values.accountName,
        walletType: selectedProxyType
      })
      
      // 等待store更新
      await new Promise(resolve => setTimeout(resolve, 100))
      
      // 获取新添加的账户ID（通过API获取，因为store可能还没更新）
      const accountsResponse = await apiService.accounts.list()
      if (accountsResponse.data.code === 0 && accountsResponse.data.data) {
        const newAccounts = accountsResponse.data.data.list || []
        const newAccount = newAccounts.find((acc: any) => acc.walletAddress === walletAddress)
        if (newAccount && onSuccess) {
          onSuccess(newAccount.id)
        } else if (onSuccess) {
          onSuccess(0)
        }
      } else if (onSuccess) {
        onSuccess(0)
      }
      
      return Promise.resolve()
    } catch (error: unknown) {
      const err = error as Error & { code?: number }
      const isDuplicate = err?.code === 4601
      message.error(isDuplicate ? t('accountImport.duplicateAccount') : (err?.message ?? t('accountImport.importFailed')))
      return Promise.reject(error)
    }
  }
  
  const currentStep = step === 'input' ? 0 : 1

  return (
    <div style={{ padding: isMobile ? '0 4px' : '0 8px' }}>
      <Steps
        current={currentStep}
        size="small"
        style={{ marginBottom: 24 }}
        items={[
          { title: t('accountImport.importMethod'), icon: <KeyOutlined /> },
          { title: t('accountImport.selectProxyOption'), icon: <WalletOutlined /> },
          { title: t('accountImport.accountName'), icon: <UserOutlined /> }
        ]}
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        size={isMobile ? 'middle' : 'large'}
      >
        <Form.Item label={t('accountImport.importMethod')} style={{ marginBottom: 16 }}>
          <Radio.Group
            value={importType}
            onChange={(e) => {
              setImportType(e.target.value)
            }}
            optionType="button"
            buttonStyle="solid"
            size={isMobile ? 'middle' : 'large'}
          >
            <Radio.Button value="privateKey">{t('accountImport.privateKey')}</Radio.Button>
            <Radio.Button value="mnemonic">{t('accountImport.mnemonic')}</Radio.Button>
          </Radio.Group>
        </Form.Item>
        
        {importType === 'privateKey' ? (
          <>
            <Form.Item
              label={t('accountImport.privateKeyLabel')}
              name="privateKey"
              rules={[
                { required: true, message: t('accountImport.privateKeyRequired') },
                {
                  validator: (_, value) => {
                    if (!value) return Promise.resolve()
                    if (!isValidPrivateKey(value)) {
                      return Promise.reject(new Error(t('accountImport.privateKeyInvalid')))
                    }
                    return Promise.resolve()
                  }
                }
              ]}
              help={addressError || ''}
              validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
            >
              <Input.TextArea
                rows={2}
                placeholder={t('accountImport.privateKeyPlaceholder')}
                onChange={handlePrivateKeyChange}
                onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()}
                disabled={loadingProxyOptions}
              />
            </Form.Item>
            
            <Form.Item
              label={t('accountImport.walletAddress')}
              name="walletAddress"
              rules={[
                { required: true, message: t('accountImport.walletAddressRequired') },
                {
                  validator: (_, value) => {
                    if (!value) return Promise.resolve()
                    if (!isValidWalletAddress(value)) {
                      return Promise.reject(new Error(t('accountImport.walletAddressInvalid')))
                    }
                    if (derivedAddress && value !== derivedAddress) {
                      return Promise.reject(new Error(t('accountImport.walletAddressMismatch')))
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <Input
                placeholder={t('accountImport.walletAddressPlaceholder')}
                readOnly={!!derivedAddress}
                disabled={loadingProxyOptions}
              />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item
              label={t('accountImport.mnemonicLabel')}
              name="mnemonic"
              rules={[
                { required: true, message: t('accountImport.mnemonicRequired') },
                {
                  validator: (_, value) => {
                    if (!value) return Promise.resolve()
                    if (!isValidMnemonic(value)) {
                      return Promise.reject(new Error(t('accountImport.mnemonicInvalid')))
                    }
                    return Promise.resolve()
                  }
                }
              ]}
              help={addressError || ''}
              validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
            >
              <Input.TextArea
                rows={2}
                placeholder={t('accountImport.mnemonicPlaceholder')}
                onChange={handleMnemonicChange}
                onKeyDown={(e) => e.key === 'Enter' && e.preventDefault()}
                disabled={loadingProxyOptions}
              />
            </Form.Item>
            
            <Form.Item
              label={t('accountImport.walletAddress')}
              name="walletAddress"
              rules={[
                { required: true, message: t('accountImport.walletAddressRequired') },
                {
                  validator: (_, value) => {
                    if (!value) return Promise.resolve()
                    if (!isValidWalletAddress(value)) {
                      return Promise.reject(new Error(t('accountImport.walletAddressInvalid')))
                    }
                    if (derivedAddress && value !== derivedAddress) {
                      return Promise.reject(new Error(t('accountImport.walletAddressMismatchMnemonic')))
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <Input
                placeholder={t('accountImport.walletAddressPlaceholder')}
                readOnly={!!derivedAddress}
                disabled={loadingProxyOptions}
              />
            </Form.Item>
          </>
        )}
        
        {/* 请求代理地址时的 loading 提示 */}
        {loadingProxyOptions && step === 'input' && (
          <Form.Item>
            <Alert
              message={
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Spin size="small" />
                  <span>{t('accountImport.loadingProxyOptions')}</span>
                </div>
              }
              type="info"
              showIcon={false}
              style={{ marginBottom: 16 }}
            />
          </Form.Item>
        )}
        
        {/* 代理地址选项选择 */}
        {step === 'select' && (
          <Form.Item
            label={t('accountImport.selectProxyOption')}
            required
            rules={[
              {
                validator: () => {
                  if (!selectedProxyType) {
                    return Promise.reject(new Error(t('accountImport.proxyOptionRequired')))
                  }
                  return Promise.resolve()
                }
              }
            ]}
            style={{ marginBottom: 20 }}
          >
            {loadingProxyOptions ? (
              <div style={{ padding: '32px 0', textAlign: 'center' }}>
                <Spin tip={t('accountImport.loadingProxyOptions')} />
              </div>
            ) : (
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                {proxyOptions.map((option) => {
                  const isSelected = selectedProxyType === option.walletType
                  const typeLabel = option.walletType.toLowerCase() === 'magic' ? 'Magic' : 'Safe'
                  return (
                    <Card
                      key={option.walletType}
                      hoverable
                      onClick={() => setSelectedProxyType(option.walletType)}
                      size="small"
                      style={{
                        cursor: 'pointer',
                        borderColor: isSelected ? 'var(--ant-color-primary)' : undefined,
                        borderWidth: isSelected ? 2 : 1,
                        backgroundColor: isSelected ? 'var(--ant-color-primary-bg)' : undefined,
                        transition: 'border-color 0.2s, background-color 0.2s'
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
                        <Space size="middle">
                          <Radio checked={isSelected} />
                          <Tag color={option.walletType.toLowerCase() === 'magic' ? 'purple' : 'blue'}>
                            {typeLabel}
                          </Tag>
                          {option.hasAssets && (
                            <span style={{ color: '#52c41a', fontSize: 12 }}>
                              <CheckCircleOutlined /> {t('accountImport.proxyOption.hasAssets')}
                            </span>
                          )}
                          {option.error && (
                            <span style={{ color: 'var(--ant-color-error)', fontSize: 12 }}>
                              <ExclamationCircleOutlined /> {t('accountImport.proxyOption.error')}
                            </span>
                          )}
                        </Space>
                        {!option.error && (
                          <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--ant-color-primary)' }}>
                            {formatUSDC(option.totalBalance)} USDC
                          </span>
                        )}
                      </div>
                      <div style={{ marginTop: 8, marginLeft: 28, fontSize: 12, color: 'var(--ant-color-text-secondary)', wordBreak: 'break-all' }}>
                        {option.proxyAddress ? (
                          <span style={{ fontFamily: 'monospace' }}>{option.proxyAddress}</span>
                        ) : (
                          '-'
                        )}
                        {option.error && (
                          <span style={{ color: 'var(--ant-color-error)', marginLeft: 8 }}>{option.error}</span>
                        )}
                      </div>
                      <div style={{ marginTop: 8, marginLeft: 28, fontSize: 12, color: 'var(--ant-color-text-secondary)', lineHeight: 1.5 }}>
                        {t('accountImport.proxyOption.proxyAddressHelp')}
                      </div>
                    </Card>
                  )
                })}
              </Space>
            )}
          </Form.Item>
        )}
        
        <Form.Item
          label={t('accountImport.accountName')}
          name="accountName"
          style={{ marginBottom: 24 }}
        >
          <Input placeholder={t('accountImport.accountNamePlaceholder')} />
        </Form.Item>
        
        <Form.Item style={{ marginBottom: 0 }}>
          <Space size="middle">
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              disabled={step !== 'select' || !selectedProxyType || loadingProxyOptions}
              size={isMobile ? 'middle' : 'large'}
              style={isMobile ? { minHeight: 44 } : undefined}
            >
              {t('accountImport.importAccount')}
            </Button>
            {showCancelButton && onCancel && (
              <Button onClick={onCancel} size={isMobile ? 'middle' : 'large'}>
                {t('common.cancel')}
              </Button>
            )}
          </Space>
        </Form.Item>
      </Form>
    </div>
  )
}

export default AccountImportForm
