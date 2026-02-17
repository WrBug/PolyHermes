import React, { useEffect, useState } from 'react'
import { Card, Steps, Button, Space, Tag, Spin, Typography, message } from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  WalletOutlined,
  KeyOutlined,
  SafetyOutlined,
  LinkOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'

const { Paragraph, Text } = Typography

export interface SetupStatus {
  proxyDeployed: boolean
  tradingEnabled: boolean
  tokensApproved: boolean
  approvalDetails?: Record<string, string>
  error?: string
}

interface AccountSetupStatusBlockProps {
  accountId: number
  onRefresh?: () => void
  onAllCompleted?: () => void
  size?: 'small' | 'default'
  showApprovalDetails?: boolean
  /** 嵌入模式：不渲染 Card，仅渲染步骤与授权详情（供弹窗等复用） */
  embedded?: boolean
}

/** 步骤 key 与步骤编号对应 */
const STEP_KEYS = ['step1', 'step2', 'step3'] as const
const stepKeyToNumber = (key: string): number =>
  STEP_KEYS.indexOf(key as typeof STEP_KEYS[number]) + 1

const AccountSetupStatusBlock: React.FC<AccountSetupStatusBlockProps> = ({
  accountId,
  onRefresh,
  onAllCompleted,
  size = 'default',
  showApprovalDetails = true,
  embedded = false
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [setupStatus, setSetupStatus] = useState<SetupStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)

  const fetchStatus = async () => {
    if (accountId <= 0) return
    try {
      const response = await apiService.accounts.checkSetupStatus(accountId)
      if (response.data.code === 0 && response.data.data) {
        setSetupStatus(response.data.data)
      } else {
        setSetupStatus(null)
      }
    } catch (error) {
      console.error('获取账户设置状态失败:', error)
      setSetupStatus(null)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }

  useEffect(() => {
    setLoading(true)
    fetchStatus()
  }, [accountId])

  // 每 5 秒轮询最新状态（首次加载完成后且存在未完成步骤时轮询，全部完成后停止）
  useEffect(() => {
    if (accountId <= 0 || setupStatus == null) return
    const allCompleted =
      setupStatus.proxyDeployed &&
      setupStatus.tradingEnabled &&
      setupStatus.tokensApproved
    if (allCompleted) return
    const timer = setInterval(() => {
      fetchStatus()
    }, 5000)
    return () => clearInterval(timer)
  }, [accountId, setupStatus?.proxyDeployed, setupStatus?.tradingEnabled, setupStatus?.tokensApproved])

  // 全部完成时通知父组件（供弹窗等关闭或更新用）
  const allCompleted =
    setupStatus != null &&
    setupStatus.proxyDeployed &&
    setupStatus.tradingEnabled &&
    setupStatus.tokensApproved
  useEffect(() => {
    if (allCompleted) onAllCompleted?.()
  }, [allCompleted, onAllCompleted])

  const handleRefresh = async () => {
    setRefreshing(true)
    await fetchStatus()
    onRefresh?.()
  }

  const handleStepAction = async (key: string) => {
    const stepNum = stepKeyToNumber(key)
    if (stepNum < 1) return
    setActionLoading(key)
    try {
      const response = await apiService.accounts.executeSetupStep(accountId, stepNum)
      const res = response.data
      if (res.code !== 0) {
        message.error(res.msg || t('accountSetup.actionFailed'))
        return
      }
      const data = res.data
      if (data?.redirectUrl) {
        window.open(data.redirectUrl, '_blank')
      }
      if (data?.success !== false) {
        await fetchStatus()
        onRefresh?.()
        if (data?.transactionHash) {
          message.success(t('accountSetup.actionSuccess'))
        }
      }
    } catch (err) {
      message.error(t('accountSetup.actionFailed'))
    } finally {
      setActionLoading(null)
    }
  }

  if (loading && !setupStatus) {
    const loadingContent = (
      <div style={{ textAlign: 'center', padding: '24px 0' }}>
        <Spin />
      </div>
    )
    return embedded ? <div>{loadingContent}</div> : (
      <Card title={t('accountSetup.title')} size={size}>{loadingContent}</Card>
    )
  }

  if (!setupStatus) {
    const errorContent = (
      <>
        <Text type="secondary">{t('accountSetup.error.description')}</Text>
        <div style={{ marginTop: 12 }}>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh}>
            {t('accountSetup.refresh')}
          </Button>
        </div>
      </>
    )
    return embedded ? <div>{errorContent}</div> : (
      <Card title={t('accountSetup.title')} size={size}>{errorContent}</Card>
    )
  }

  const steps = [
    {
      key: 'step1',
      title: t('accountSetup.step1.title'),
      description: t('accountSetup.step1.description'),
      icon: <WalletOutlined />,
      completed: setupStatus.proxyDeployed,
      actionLabel: t('accountSetup.step1.action')
    },
    {
      key: 'step2',
      title: t('accountSetup.step2.title'),
      description: t('accountSetup.step2.description'),
      icon: <KeyOutlined />,
      completed: setupStatus.tradingEnabled,
      actionLabel: t('accountSetup.step2.action')
    },
    {
      key: 'step3',
      title: t('accountSetup.step3.title'),
      description: t('accountSetup.step3.description'),
      icon: <SafetyOutlined />,
      completed: setupStatus.tokensApproved,
      actionLabel: t('accountSetup.step3.action')
    }
  ]

  const stepsContent = (
    <>
      <Steps
        direction="vertical"
        current={steps.findIndex(s => !s.completed)}
        size="small"
        style={{ marginBottom: 16 }}
      >
        {steps.map((step) => (
          <Steps.Step
            key={step.key}
            title={
              <Space>
                <span>{step.title}</span>
                {step.completed ? (
                  <Tag color="success" icon={<CheckCircleOutlined />}>
                    {t('accountSetup.completed')}
                  </Tag>
                ) : (
                  <Tag color="warning" icon={<CloseCircleOutlined />}>
                    {t('accountSetup.pending')}
                  </Tag>
                )}
              </Space>
            }
            description={
              <div style={{ marginTop: 8 }}>
                <Paragraph style={{ marginBottom: 8, fontSize: 14, color: '#666' }}>
                  {step.description}
                </Paragraph>
                {!step.completed && (
                  <Button
                    type="primary"
                    size="small"
                    icon={<LinkOutlined />}
                    onClick={() => handleStepAction(step.key)}
                    loading={actionLoading === step.key}
                    style={{ marginTop: 4 }}
                  >
                    {step.actionLabel}
                  </Button>
                )}
              </div>
            }
            icon={step.icon}
            status={step.completed ? 'finish' : 'process'}
          />
        ))}
      </Steps>

      {showApprovalDetails && setupStatus.approvalDetails && Object.keys(setupStatus.approvalDetails).length > 0 && (
        <div style={{ marginTop: 16, padding: '12px', background: '#fafafa', borderRadius: 4 }}>
          <Text strong style={{ display: 'block', marginBottom: 8 }}>{t('accountSetup.approvalDetails.title')}</Text>
          <Space direction="vertical" style={{ width: '100%' }} size="small">
            {Object.entries(setupStatus.approvalDetails).map(([contract, allowance]) => {
              const isUnlimited = allowance === 'unlimited'
              const isApproved = isUnlimited || parseFloat(allowance) > 0
              const displayText = isUnlimited
                ? t('accountSetup.approvalDetails.unlimited')
                : isApproved
                  ? `${parseFloat(allowance).toFixed(2)} USDC`
                  : t('accountSetup.approvalDetails.notApproved')
              return (
                <div
                  key={contract}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    fontSize: 13,
                    minHeight: 24
                  }}
                >
                  <span>{t(`accountSetup.approvalDetails.${contract}`) || contract}</span>
                  <span style={{ minWidth: 100, textAlign: 'right' }}>{displayText}</span>
                </div>
              )
            })}
          </Space>
        </div>
      )}

      {setupStatus.error && (
        <div style={{ marginTop: 12 }}>
          <Text type="danger">{setupStatus.error}</Text>
        </div>
      )}
    </>
  )

  if (embedded) {
    return <div style={{ position: 'relative' }}>{stepsContent}</div>
  }

  return (
    <Card
      title={t('accountSetup.title')}
      size={size}
      extra={
        <Button
          type="text"
          size="small"
          icon={<ReloadOutlined />}
          onClick={handleRefresh}
          loading={refreshing}
        >
          {t('accountSetup.refresh')}
        </Button>
      }
    >
      {stepsContent}
    </Card>
  )
}

export default AccountSetupStatusBlock
