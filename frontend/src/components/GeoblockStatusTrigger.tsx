import { useMemo } from 'react'
import { Button, Popover, Space } from 'antd'
import { GlobalOutlined, LinkOutlined, LoadingOutlined, ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useGeoblockCheck } from '../hooks/useGeoblockCheck'

const GEOBLOCK_DOCS_URL = 'https://docs.polymarket.com/api-reference/geoblock'

type StatusTone = 'loading' | 'ok' | 'blocked' | 'warn'

const TONE_COLOR: Record<StatusTone, string> = {
  loading: 'rgba(255, 255, 255, 0.45)',
  ok: '#52c41a',
  blocked: '#ff7875',
  warn: '#faad14'
}

function formatLocation(country: string, region: string): string {
  if (country && region) {
    return `${country}/${region}`
  }
  return country || region || '—'
}

interface GeoblockStatusTriggerProps {
  iconSize?: number
  dotSize?: number
}

const GeoblockStatusTrigger: React.FC<GeoblockStatusTriggerProps> = ({ iconSize = 18, dotSize = 12 }) => {
  const { t } = useTranslation()
  const { status, data, refresh, loading } = useGeoblockCheck(true)

  const locationText = useMemo(() => {
    if (!data) return '—'
    return formatLocation(data.country, data.region)
  }, [data])

  let tone: StatusTone = 'loading'
  let label = t('geoblock.checkingShort')

  if (status === 'loading' || status === 'idle') {
    tone = 'loading'
    label = t('geoblock.checkingShort')
  } else if (status === 'error') {
    tone = 'warn'
    label = t('geoblock.unknown.short')
  } else if (data?.blocked) {
    tone = 'blocked'
    label = t('geoblock.menu.blocked', { location: locationText })
  } else if (status === 'success' && data) {
    tone = 'ok'
    label = t('geoblock.menu.ok', { location: locationText })
  }

  const accent = TONE_COLOR[tone]

  const popoverContent = (
    <div style={{ maxWidth: 240 }}>
      <div style={{ fontWeight: 500, marginBottom: 6 }}>{t('geoblock.title')}</div>
      <div style={{ fontSize: 13, marginBottom: 8, lineHeight: 1.5 }}>{label}</div>
      {data && (
        <div style={{ fontSize: 12, color: 'rgba(0, 0, 0, 0.45)', marginBottom: 10 }}>
          <div>IP: {data.ip || '—'}</div>
          <div>{t('geoblock.location')}: {locationText}</div>
        </div>
      )}
      <Space size={8}>
        <Button
          type="link"
          size="small"
          icon={loading ? <LoadingOutlined /> : <ReloadOutlined />}
          onClick={() => refresh()}
          loading={loading}
          style={{ padding: 0, height: 'auto' }}
        >
          {t('geoblock.refresh')}
        </Button>
        <Button
          type="link"
          size="small"
          icon={<LinkOutlined />}
          href={GEOBLOCK_DOCS_URL}
          target="_blank"
          rel="noopener noreferrer"
          style={{ padding: 0, height: 'auto' }}
        >
          {t('geoblock.viewDocsShort')}
        </Button>
      </Space>
    </div>
  )

  return (
    <Popover content={popoverContent} trigger="click" placement="bottom">
      <button
        type="button"
        title={label}
        aria-label={t('geoblock.title')}
        style={{
          position: 'relative',
          color: '#fff',
          fontSize: iconSize,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          padding: 0,
          lineHeight: 1
        }}
      >
        {tone === 'loading' ? (
          <LoadingOutlined style={{ fontSize: iconSize, color: accent }} />
        ) : (
          <GlobalOutlined style={{ fontSize: iconSize }} />
        )}
        {tone !== 'loading' && (
          <span
            style={{
              position: 'absolute',
              right: -Math.round(dotSize / 3),
              bottom: -Math.round(dotSize / 3),
              width: dotSize,
              height: dotSize,
              borderRadius: '50%',
              background: accent,
              border: '2px solid #001529',
              boxShadow: tone === 'ok' ? `0 0 5px ${accent}` : undefined
            }}
          />
        )}
      </button>
    </Popover>
  )
}

export default GeoblockStatusTrigger
