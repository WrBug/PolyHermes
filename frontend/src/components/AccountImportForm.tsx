import { useState } from 'react'
import { Form, Input, Button, Radio, Space, Alert, Tooltip } from 'antd'
import { QuestionCircleOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useAccountStore } from '../store/accountStore'
import {
  getAddressFromPrivateKey,
  getAddressFromMnemonic,
  getPrivateKeyFromMnemonic,
  isValidWalletAddress,
  isValidPrivateKey,
  isValidMnemonic
} from '../utils'
import { useMediaQuery } from 'react-responsive'

type ImportType = 'privateKey' | 'mnemonic'
type WalletType = 'magic' | 'safe'

interface AccountImportFormProps {
  form: any
  onSuccess?: (accountId: number) => void
  onCancel?: () => void
  showAlert?: boolean
  showCancelButton?: boolean
}

const AccountImportForm: React.FC<AccountImportFormProps> = ({
  form,
  onSuccess,
  onCancel,
  showAlert = true,
  showCancelButton = true
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { importAccount, loading } = useAccountStore()
  const [importType, setImportType] = useState<ImportType>('privateKey')
  const [walletType, setWalletType] = useState<WalletType>('magic')
  const [derivedAddress, setDerivedAddress] = useState<string>('')
  const [addressError, setAddressError] = useState<string>('')
  
  // 当私钥输入时，自动推导地址
  const handlePrivateKeyChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const privateKey = e.target.value.trim()
    if (!privateKey) {
      setDerivedAddress('')
      setAddressError('')
      return
    }
    
    // 验证私钥格式
    if (!isValidPrivateKey(privateKey)) {
      setAddressError(t('accountImport.privateKeyInvalid'))
      setDerivedAddress('')
      return
    }
    
    try {
      const address = getAddressFromPrivateKey(privateKey)
      setDerivedAddress(address)
      setAddressError('')
      
      // 自动填充钱包地址字段
      form.setFieldsValue({ walletAddress: address })
    } catch (error: any) {
      setAddressError(error.message || t('accountImport.addressError'))
      setDerivedAddress('')
    }
  }
  
  // 当助记词输入时，自动推导地址
  const handleMnemonicChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const mnemonic = e.target.value.trim()
    if (!mnemonic) {
      setDerivedAddress('')
      setAddressError('')
      return
    }
    
    // 验证助记词格式
    if (!isValidMnemonic(mnemonic)) {
      setAddressError(t('accountImport.mnemonicInvalid'))
      setDerivedAddress('')
      return
    }
    
    try {
      const address = getAddressFromMnemonic(mnemonic, 0)
      setDerivedAddress(address)
      setAddressError('')
      
      // 自动填充钱包地址字段
      form.setFieldsValue({ walletAddress: address })
    } catch (error: any) {
      setAddressError(error.message || t('accountImport.addressErrorMnemonic'))
      setDerivedAddress('')
    }
  }
  
  const handleSubmit = async (values: any) => {
    try {
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
            // 地址不匹配，使用推导的地址（因为私钥是从助记词导出的，必须使用对应的地址）
            walletAddress = derivedAddressFromMnemonic
          } else {
            // 地址匹配，使用用户输入的地址
            walletAddress = values.walletAddress
          }
        } else {
          // 如果用户没有输入地址，使用推导的地址
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
        walletType: walletType
      })
      
      // 等待store更新
      await new Promise(resolve => setTimeout(resolve, 100))
      
      // 获取新添加的账户ID（通过API获取，因为store可能还没更新）
      const { apiService } = await import('../services/api')
      const accountsResponse = await apiService.accounts.list()
      if (accountsResponse.data.code === 0 && accountsResponse.data.data) {
        const newAccounts = accountsResponse.data.data.list || []
        const newAccount = newAccounts.find((acc: any) => acc.walletAddress === walletAddress)
        if (newAccount && onSuccess) {
          onSuccess(newAccount.id)
        } else if (onSuccess) {
          // 如果找不到账户，仍然调用onSuccess（可能在其他地方处理）
          onSuccess(0)
        }
      } else if (onSuccess) {
        // API调用失败，仍然调用onSuccess
        onSuccess(0)
      }
      
      return Promise.resolve()
    } catch (error: any) {
      return Promise.reject(error)
    }
  }
  
  return (
    <>
      {showAlert && (
        <Alert
          message={t('accountImport.securityTip')}
          description={t('accountImport.securityTipDesc')}
          type="warning"
          showIcon
          style={{ marginBottom: '24px' }}
        />
      )}
      
      <Form
        form={form}
        layout="vertical"
        onFinish={handleSubmit}
        size={isMobile ? 'middle' : 'large'}
      >
        <Form.Item label={t('accountImport.importMethod')}>
          <Radio.Group
            value={importType}
            onChange={(e) => {
              setImportType(e.target.value)
              setDerivedAddress('')
              setAddressError('')
              form.setFieldsValue({ walletAddress: '' })
            }}
          >
            <Radio value="privateKey">{t('accountImport.privateKey')}</Radio>
            <Radio value="mnemonic">{t('accountImport.mnemonic')}</Radio>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          label={
            <span>
              {t('accountImport.walletType')}{' '}
              <Tooltip title={t('accountImport.walletTypeHelp')}>
                <QuestionCircleOutlined style={{ color: '#999' }} />
              </Tooltip>
            </span>
          }
        >
          <Radio.Group
            value={walletType}
            onChange={(e) => setWalletType(e.target.value)}
          >
            <Radio value="magic">
              {t('accountImport.walletTypeMagic')}
            </Radio>
            <Radio value="safe">
              {t('accountImport.walletTypeSafe')}
            </Radio>
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
              help={addressError || (derivedAddress ? `${t('accountImport.derivedAddress')}: ${derivedAddress}` : '')}
              validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
            >
              <Input.TextArea
                rows={3}
                placeholder={t('accountImport.privateKeyPlaceholder')}
                onChange={handlePrivateKeyChange}
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
              help={addressError || (derivedAddress ? `${t('accountImport.derivedAddress')}: ${derivedAddress}` : '')}
              validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
            >
              <Input.TextArea
                rows={4}
                placeholder={t('accountImport.mnemonicPlaceholder')}
                onChange={handleMnemonicChange}
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
              />
            </Form.Item>
          </>
        )}
        
        <Form.Item
          label={t('accountImport.accountName')}
          name="accountName"
        >
          <Input placeholder={t('accountImport.accountNamePlaceholder')} />
        </Form.Item>
        
        <Form.Item>
          <Space>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              size={isMobile ? 'middle' : 'large'}
            >
              {t('accountImport.importAccount')}
            </Button>
            {showCancelButton && onCancel && (
              <Button onClick={onCancel}>
                {t('common.cancel')}
              </Button>
            )}
          </Space>
        </Form.Item>
      </Form>
    </>
  )
}

export default AccountImportForm

