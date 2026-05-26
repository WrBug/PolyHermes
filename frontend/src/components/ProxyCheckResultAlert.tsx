import { Alert, Tag, Typography } from 'antd'
import { CloseCircleOutlined, WarningOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'

const { Text } = Typography

export interface ProxyCheckGeoblockResult {
  checked: boolean
  blocked?: boolean | null
  ip?: string | null
  country?: string | null
  region?: string | null
  message?: string | null
}

export interface ProxyCheckResponse {
  success: boolean
  message: string
  responseTime?: number
  latency?: number
  geoblock?: ProxyCheckGeoblockResult | null
}

interface ProxyCheckResultAlertProps {
  result: ProxyCheckResponse
  style?: React.CSSProperties
}

interface ResultRowProps {
  label: string
  children: React.ReactNode
  fullWidth?: boolean
  isMobile: boolean
}

function formatLocation(country?: string | null, region?: string | null): string {
  if (country && region) {
    return `${country} / ${region}`
  }
  return country || region || '—'
}

function stripGeoblockSuffix(message: string, geoblockMessage?: string | null): string {
  if (!geoblockMessage) {
    return message
  }
  const suffix = `；${geoblockMessage}`
  if (message.endsWith(suffix)) {
    return message.slice(0, -suffix.length)
  }
  const semi = message.indexOf('；')
  if (semi > 0) {
    return message.slice(0, semi)
  }
  return message
}

const ResultRow: React.FC<ResultRowProps> = ({ label, children, fullWidth, isMobile }) => (
  <div
    style={{
      gridColumn: fullWidth && !isMobile ? '1 / -1' : undefined,
      display: 'flex',
      alignItems: 'baseline',
      gap: 12,
      minWidth: 0,
      lineHeight: 1.5
    }}
  >
    <Text type="secondary" style={{ flexShrink: 0, width: isMobile ? 96 : 108, fontSize: 13 }}>
      {label}
    </Text>
    <div style={{ flex: 1, minWidth: 0, fontSize: 13 }}>{children}</div>
  </div>
)

const ProxyCheckResultAlert: React.FC<ProxyCheckResultAlertProps> = ({ result, style }) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  const geoblock = result.geoblock
  const geoblockChecked = Boolean(geoblock?.checked)
  const geoblockBlocked = geoblockChecked && geoblock?.blocked === true
  const geoblockUnknown = geoblockChecked && geoblock?.blocked == null

  const alertType = !result.success ? 'error' : geoblockBlocked ? 'warning' : 'success'
  const alertMessage = !result.success
    ? t('proxySettings.checkFailed')
    : geoblockBlocked
      ? t('proxySettings.checkSuccessWithGeoblockWarning')
      : t('proxySettings.checkSuccess')

  const latencyMs = result.latency ?? result.responseTime
  const connectionSummary = stripGeoblockSuffix(result.message, geoblock?.message)
  const locationText = formatLocation(geoblock?.country, geoblock?.region)

  const renderGeoblockValue = () => {
    if (!geoblockChecked) {
      return null
    }
    if (geoblockUnknown) {
      return (
        <Tag icon={<WarningOutlined />} color="warning">
          {t('proxySettings.checkResult.geoblockUnknown')}
        </Tag>
      )
    }
    if (geoblockBlocked) {
      return (
        <Tag icon={<CloseCircleOutlined />} color="error">
          {t('proxySettings.checkResult.geoblockBlocked')}
        </Tag>
      )
    }
    return <Text>{t('proxySettings.checkResult.geoblockOk')}</Text>
  }

  return (
    <Alert
      type={alertType}
      message={alertMessage}
      description={
        <div
          style={{
            marginTop: 8,
            display: 'grid',
            gridTemplateColumns: isMobile ? '1fr' : '1fr 1fr',
            gap: isMobile ? 10 : '10px 24px'
          }}
        >
          <ResultRow label={t('proxySettings.checkResult.connection')} isMobile={isMobile}>
            {result.success ? (
              <Text>{t('proxySettings.checkResult.connected')}</Text>
            ) : (
              <Tag icon={<CloseCircleOutlined />} color="error">
                {t('proxySettings.checkResult.failed')}
              </Tag>
            )}
          </ResultRow>

          {latencyMs !== undefined && (
            <ResultRow label={t('proxySettings.checkResult.latency')} isMobile={isMobile}>
              <Text type={latencyMs >= 3000 ? 'warning' : undefined}>{latencyMs} ms</Text>
            </ResultRow>
          )}

          {geoblockChecked && (
            <>
              <ResultRow label={t('proxySettings.geoblockTitle')} isMobile={isMobile}>
                {renderGeoblockValue()}
              </ResultRow>
              <ResultRow label={t('geoblock.location')} isMobile={isMobile}>
                <Text>{locationText}</Text>
              </ResultRow>
              {geoblock?.ip && (
                <ResultRow label={t('geoblock.ip')} isMobile={isMobile} fullWidth>
                  <Text style={{ wordBreak: 'break-all' }}>{geoblock.ip}</Text>
                </ResultRow>
              )}
              {geoblockUnknown && geoblock?.message && (
                <ResultRow label={t('proxySettings.checkResult.detail')} isMobile={isMobile} fullWidth>
                  <Text type="secondary">{geoblock.message}</Text>
                </ResultRow>
              )}
            </>
          )}

          {!result.success && connectionSummary && (
            <ResultRow label={t('proxySettings.checkResult.detail')} isMobile={isMobile} fullWidth>
              <Text type="secondary">{connectionSummary}</Text>
            </ResultRow>
          )}
        </div>
      }
      style={style}
      showIcon
    />
  )
}

export default ProxyCheckResultAlert
