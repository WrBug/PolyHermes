import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Input, Button, message, Typography, Radio, Space, Alert, Checkbox } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useAccountStore } from '../store/accountStore'
import { 
  getAddressFromPrivateKey, 
  getAddressFromMnemonic,
  getPrivateKeyFromMnemonic,
  isValidWalletAddress, 
  isValidPrivateKey,
  isValidMnemonic
} from '../utils/ethers'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

type ImportType = 'privateKey' | 'mnemonic'

const AccountImport: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { importAccount, loading } = useAccountStore()
  const [form] = Form.useForm()
  const [importType, setImportType] = useState<ImportType>('privateKey')
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
      setAddressError('私钥格式不正确（应为64位十六进制字符串）')
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
      setAddressError(error.message || '无法从私钥推导地址')
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
      setAddressError('助记词格式不正确（应为12或24个单词，用空格分隔）')
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
      setAddressError(error.message || '无法从助记词推导地址')
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
          message.error('钱包地址与私钥不匹配')
          return
        }
      } else {
        // 助记词模式
        if (!values.mnemonic) {
          message.error('请输入助记词')
          return
        }
        
        // 从助记词导出私钥和地址
        privateKey = getPrivateKeyFromMnemonic(values.mnemonic, 0)
        const derivedAddressFromMnemonic = getAddressFromMnemonic(values.mnemonic, 0)
        
        // 如果用户手动输入了地址，验证是否与推导的地址一致
        if (values.walletAddress) {
          if (values.walletAddress !== derivedAddressFromMnemonic) {
            // 地址不匹配，使用推导的地址（因为私钥是从助记词导出的，必须使用对应的地址）
            message.warning(`输入的地址与助记词推导的地址不一致。推导的地址: ${derivedAddressFromMnemonic}，将使用推导的地址`)
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
        message.error('钱包地址格式不正确')
        return
      }
      
      await importAccount({
        privateKey: privateKey,
        walletAddress: walletAddress,
        accountName: values.accountName,
        isDefault: values.isDefault || false
      })
      
      message.success('导入账户成功')
      navigate('/accounts')
    } catch (error: any) {
      message.error(error.message || '导入账户失败')
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/accounts')}
          style={{ marginBottom: '16px' }}
        >
          返回
        </Button>
        <Title level={2} style={{ margin: 0 }}>导入账户</Title>
      </div>
      
      <Card>
        <Alert
          message="安全提示"
          description="私钥将存储在后端数据库中，请确保数据库访问安全。建议使用 HTTPS 连接。"
          type="warning"
          showIcon
          style={{ marginBottom: '24px' }}
        />
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item label="导入方式">
            <Radio.Group 
              value={importType} 
              onChange={(e) => {
                setImportType(e.target.value)
                setDerivedAddress('')
                setAddressError('')
                form.setFieldsValue({ walletAddress: '' })
              }}
            >
              <Radio value="privateKey">私钥</Radio>
              <Radio value="mnemonic">助记词</Radio>
            </Radio.Group>
          </Form.Item>
          
          {importType === 'privateKey' ? (
            <>
              <Form.Item
                label="私钥"
                name="privateKey"
                rules={[
                  { required: true, message: '请输入私钥' },
                  {
                    validator: (_, value) => {
                      if (!value) return Promise.resolve()
                      if (!isValidPrivateKey(value)) {
                        return Promise.reject(new Error('私钥格式不正确（应为64位十六进制字符串）'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
                help={addressError || (derivedAddress ? `推导地址: ${derivedAddress}` : '')}
                validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
              >
                <Input.TextArea
                  rows={3}
                  placeholder="请输入私钥（64位十六进制字符串，可选0x前缀）"
                  onChange={handlePrivateKeyChange}
                />
              </Form.Item>
              
              <Form.Item
                label="钱包地址"
                name="walletAddress"
                rules={[
                  { required: true, message: '请输入钱包地址' },
                  {
                    validator: (_, value) => {
                      if (!value) return Promise.resolve()
                      if (!isValidWalletAddress(value)) {
                        return Promise.reject(new Error('钱包地址格式不正确'))
                      }
                      if (derivedAddress && value !== derivedAddress) {
                        return Promise.reject(new Error('钱包地址与私钥不匹配'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <Input
                  placeholder="钱包地址（将从私钥自动推导）"
                  readOnly={!!derivedAddress}
                />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                label="助记词"
                name="mnemonic"
                rules={[
                  { required: true, message: '请输入助记词' },
                  {
                    validator: (_, value) => {
                      if (!value) return Promise.resolve()
                      if (!isValidMnemonic(value)) {
                        return Promise.reject(new Error('助记词格式不正确（应为12或24个单词，用空格分隔）'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
                help={addressError || (derivedAddress ? `推导地址: ${derivedAddress}` : '')}
                validateStatus={addressError ? 'error' : derivedAddress ? 'success' : ''}
              >
                <Input.TextArea
                  rows={4}
                  placeholder="请输入12或24个单词的助记词（用空格分隔）"
                  onChange={handleMnemonicChange}
                />
              </Form.Item>
              
              <Form.Item
                label="钱包地址"
                name="walletAddress"
                rules={[
                  { required: true, message: '请输入钱包地址' },
                  {
                    validator: (_, value) => {
                      if (!value) return Promise.resolve()
                      if (!isValidWalletAddress(value)) {
                        return Promise.reject(new Error('钱包地址格式不正确'))
                      }
                      if (derivedAddress && value !== derivedAddress) {
                        return Promise.reject(new Error('钱包地址与助记词不匹配'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <Input
                  placeholder="钱包地址（将从助记词自动推导）"
                  readOnly={!!derivedAddress}
                />
              </Form.Item>
            </>
          )}
          
          <Form.Item
            label="账户名称"
            name="accountName"
          >
            <Input placeholder="可选，用于标识账户" />
          </Form.Item>
          
          <Alert
            message="API Key 自动获取"
            description="系统将自动从 Polymarket 获取或创建 API Key，无需手动输入。"
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
              >
                导入账户
              </Button>
              <Button onClick={() => navigate('/accounts')}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default AccountImport

