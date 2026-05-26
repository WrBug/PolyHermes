import { Checkbox, Tag, Typography } from 'antd'
import { LinkOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { parseMarketOutcomes, pickMarketImageUrl } from '../constants/whaleMonitor'
import type { WhaleMonitorMarketItem } from '../types'
import { formatUSDC } from '../utils'
import WhaleMonitorMarketThumbnail from './WhaleMonitorMarketThumbnail'

const { Text } = Typography

interface WhaleMonitorMarketListItemProps {
  market: WhaleMonitorMarketItem
  checked: boolean
  isMobile: boolean
  hideEventTitle?: boolean
  compact?: boolean
  onToggle: (checked: boolean) => void
}

const formatConditionId = (id: string): string =>
  id.length > 20 ? `${id.slice(0, 10)}...${id.slice(-6)}` : id

const WhaleMonitorMarketListItem: React.FC<WhaleMonitorMarketListItemProps> = ({
  market,
  checked,
  isMobile,
  hideEventTitle = false,
  compact = false,
  onToggle
}) => {
  const { t } = useTranslation()
  const outcomeLabels = parseMarketOutcomes(market.outcomes)
  const volumeDisplay =
    market.volume && parseFloat(market.volume) > 0 ? formatUSDC(market.volume) : null
  const polymarketUrl = market.slug ? `https://polymarket.com/event/${market.slug}` : null
  const imageUrl = pickMarketImageUrl(market)
  const thumbSize = compact ? 36 : isMobile ? 40 : 44

  const handleOpenLink = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (polymarketUrl) {
      window.open(polymarketUrl, '_blank', 'noopener,noreferrer')
    }
  }

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onToggle(!checked)}
      onKeyDown={e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onToggle(!checked)
        }
      }}
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 12,
        padding: compact ? (isMobile ? '8px 8px' : '8px 10px') : isMobile ? '12px 8px' : '10px 12px',
        borderRadius: 8,
        cursor: 'pointer',
        minHeight: 44,
        background: checked ? 'rgba(22, 119, 255, 0.06)' : undefined,
        border: checked ? '1px solid rgba(22, 119, 255, 0.3)' : '1px solid transparent'
      }}
    >
      <Checkbox
        checked={checked}
        onClick={e => e.stopPropagation()}
        onChange={e => onToggle(e.target.checked)}
        style={{ marginTop: 2 }}
      />
      <WhaleMonitorMarketThumbnail src={imageUrl} size={thumbSize} />
      <div style={{ flex: 1, minWidth: 0 }}>
        {market.eventTitle && !hideEventTitle && (
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>
            {market.eventTitle}
          </Text>
        )}
        <div style={{ fontWeight: 500, wordBreak: 'break-word', lineHeight: 1.4 }}>{market.title}</div>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: 6,
            marginTop: 6
          }}
        >
          {market.category && (
            <Tag style={{ margin: 0 }}>{market.category}</Tag>
          )}
          {volumeDisplay && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('whaleMonitorStrategy.marketSelect.volume')}: ${volumeDisplay}
            </Text>
          )}
        </div>
        {outcomeLabels.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 6 }}>
            {outcomeLabels.map(label => (
              <Tag key={label} bordered={false} style={{ margin: 0, fontSize: 11 }}>
                {label}
              </Tag>
            ))}
          </div>
        )}
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: 8,
            marginTop: 6
          }}
        >
          <Text type="secondary" style={{ fontSize: 11 }}>
            {formatConditionId(market.conditionId)}
          </Text>
          {polymarketUrl && (
            <a
              href={polymarketUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={handleOpenLink}
              style={{ fontSize: 12, display: 'inline-flex', alignItems: 'center', gap: 4 }}
            >
              <LinkOutlined />
              {t('whaleMonitorStrategy.marketSelect.viewMarket')}
            </a>
          )}
        </div>
      </div>
    </div>
  )
}

export default WhaleMonitorMarketListItem
