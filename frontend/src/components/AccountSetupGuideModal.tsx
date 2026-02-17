import React, { useState, useEffect } from 'react'
import { Modal, Alert, Space, Button, Typography } from 'antd'
import { CheckCircleOutlined, ExclamationCircleOutlined, WalletOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import AccountSetupStatusBlock from './AccountSetupStatusBlock'
import type { SetupStatus } from './AccountSetupStatusBlock'

const { Text } = Typography

interface AccountSetupGuideModalProps {
  visible: boolean
  setupStatus: SetupStatus | null
  accountId?: number
  onClose: () => void
  onComplete?: () => void
}

const AccountSetupGuideModal: React.FC<AccountSetupGuideModalProps> = ({
  visible,
  setupStatus: _initialStatus,
  accountId,
  onClose,
  onComplete
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [allCompleted, setAllCompleted] = useState(false)

  useEffect(() => {
    if (visible) setAllCompleted(false)
  }, [visible, accountId])

  if (!visible) return null

  return (
    <Modal
      title={
        <Space>
          <WalletOutlined style={{ fontSize: '20px', color: '#1890ff' }} />
          <span>{t('accountSetup.title')}</span>
        </Space>
      }
      open={visible}
      onCancel={onClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          {allCompleted ? (
            <Button type="primary" onClick={onClose} size={isMobile ? 'middle' : 'large'}>
              {t('common.confirm')}
            </Button>
          ) : (
            <Button onClick={onClose} size={isMobile ? 'middle' : 'large'}>
              {t('common.later')}
            </Button>
          )}
        </div>
      }
      width={isMobile ? '95%' : 680}
      style={{ top: isMobile ? 20 : 50 }}
      destroyOnClose
      maskClosable={allCompleted}
      closable
    >
      <div style={{ padding: isMobile ? '16px 0' : '24px 0' }}>
        {allCompleted ? (
          <Alert
            message={t('accountSetup.allCompleted.title')}
            description={t('accountSetup.allCompleted.description')}
            type="success"
            icon={<CheckCircleOutlined />}
            showIcon
            style={{ marginBottom: 24 }}
          />
        ) : (
          <Alert
            message={t('accountSetup.incomplete.title')}
            description={t('accountSetup.incomplete.description')}
            type="warning"
            icon={<ExclamationCircleOutlined />}
            showIcon
            style={{ marginBottom: 24 }}
          />
        )}

        {accountId != null && accountId > 0 ? (
          <AccountSetupStatusBlock
            accountId={accountId}
            embedded
            showApprovalDetails
            onAllCompleted={() => setAllCompleted(true)}
            onRefresh={onComplete}
          />
        ) : (
          <Text type="secondary">{t('accountSetup.error.description')}</Text>
        )}

        <div style={{ marginTop: 24, padding: '12px', background: '#f5f5f5', borderRadius: '4px' }}>
          <Text type="secondary" style={{ fontSize: '12px' }}>
            {t('accountSetup.help')}
          </Text>
        </div>
      </div>
    </Modal>
  )
}

export default AccountSetupGuideModal
