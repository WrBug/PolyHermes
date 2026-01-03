import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { message } from 'antd'
import AccountImportForm from '../components/AccountImportForm'

const { Title } = Typography

const AccountImport: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  
  const handleSuccess = async () => {
    message.success(t('accountImport.importSuccess'))
    navigate('/accounts')
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/accounts')}
          style={{ marginBottom: '16px' }}
        >
          {t('accountImport.back')}
        </Button>
        <Title level={2} style={{ margin: 0 }}>{t('accountImport.title')}</Title>
      </div>
      
      <Card>
        <AccountImportForm
          form={form}
          onSuccess={handleSuccess}
          onCancel={() => navigate('/accounts')}
          showAlert={true}
          showCancelButton={true}
        />
      </Card>
    </div>
  )
}

export default AccountImport

