import { useMemo } from 'react'
import { Checkbox, Collapse, Divider, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { groupMarketsBySection, type WhaleMonitorMarketGroup } from '../constants/whaleMonitor'
import type { WhaleMonitorMarketItem } from '../types'
import WhaleMonitorMarketListItem from './WhaleMonitorMarketListItem'
import WhaleMonitorMarketThumbnail from './WhaleMonitorMarketThumbnail'

const { Text, Title } = Typography

interface WhaleMonitorMarketGroupedListProps {
  markets: WhaleMonitorMarketItem[]
  selectedMap: Map<string, WhaleMonitorMarketItem>
  isMobile: boolean
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void
  onToggleGroup: (markets: WhaleMonitorMarketItem[], checked: boolean) => void
}

const renderMarketGroups = (
  groups: WhaleMonitorMarketGroup[],
  selectedMap: Map<string, WhaleMonitorMarketItem>,
  isMobile: boolean,
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void,
  onToggleGroup: (markets: WhaleMonitorMarketItem[], checked: boolean) => void,
  t: (key: string, options?: Record<string, number>) => string
) => {
  const showAsGroups = groups.length > 1 || (groups.length === 1 && groups[0].key.startsWith('event:'))

  if (!showAsGroups) {
    const flatMarkets = groups.flatMap(g => g.markets)
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: isMobile ? 4 : 8 }}>
        {flatMarkets.map(market => (
          <WhaleMonitorMarketListItem
            key={market.conditionId}
            market={market}
            checked={selectedMap.has(market.conditionId)}
            isMobile={isMobile}
            hideEventTitle
            onToggle={checked => onToggleMarket(market, checked)}
          />
        ))}
      </div>
    )
  }

  const collapseItems = groups.map(group => {
    const selectedInGroup = group.markets.filter(m => selectedMap.has(m.conditionId)).length
    const allSelected = selectedInGroup === group.markets.length && group.markets.length > 0
    const indeterminate = selectedInGroup > 0 && !allSelected

    return {
      key: group.key,
      label: (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
            width: '100%',
            paddingRight: 8
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1, minWidth: 0 }}>
            <WhaleMonitorMarketThumbnail src={group.imageUrl} size={32} alt={group.title} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <Text strong style={{ wordBreak: 'break-word' }}>
                {group.title}
              </Text>
              <Text type="secondary" style={{ fontSize: 12, marginLeft: 0, display: 'block' }}>
                {t('whaleMonitorStrategy.marketSelect.marketsInGroup', { count: group.markets.length })}
              </Text>
            </div>
          </div>
          <div onClick={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()}>
            <Checkbox
              checked={allSelected}
              indeterminate={indeterminate}
              onChange={e => onToggleGroup(group.markets, e.target.checked)}
            >
              <span style={{ fontSize: 12 }}>{t('whaleMonitorStrategy.marketSelect.selectGroup')}</span>
            </Checkbox>
          </div>
        </div>
      ),
      children: (
        <div style={{ display: 'flex', flexDirection: 'column', gap: isMobile ? 4 : 6 }}>
          {group.markets.map(market => (
            <WhaleMonitorMarketListItem
              key={market.conditionId}
              market={market}
              checked={selectedMap.has(market.conditionId)}
              isMobile={isMobile}
              hideEventTitle
              compact
              onToggle={checked => onToggleMarket(market, checked)}
            />
          ))}
        </div>
      )
    }
  })

  return (
    <Collapse
      bordered={false}
      defaultActiveKey={groups.map(g => g.key)}
      items={collapseItems}
      style={{ background: 'transparent' }}
    />
  )
}

const WhaleMonitorMarketGroupedList: React.FC<WhaleMonitorMarketGroupedListProps> = ({
  markets,
  selectedMap,
  isMobile,
  onToggleMarket,
  onToggleGroup
}) => {
  const { t } = useTranslation()

  const sections = useMemo(
    () =>
      groupMarketsBySection(markets, {
        game: t('whaleMonitorStrategy.marketSelect.sectionGame'),
        season: t('whaleMonitorStrategy.marketSelect.sectionSeason'),
        ungrouped: t('whaleMonitorStrategy.marketSelect.ungrouped')
      }),
    [markets, t]
  )

  if (sections.length === 1) {
    const section = sections[0]
    const showSectionTitle =
      markets.some(m => m.marketType === 'game' || m.marketType === 'season') ||
      section.groups.some(g => g.key.startsWith('event:'))
    return (
      <>
        {showSectionTitle && (
          <Title level={5} style={{ marginTop: 0, marginBottom: 12 }}>
            {section.title}
          </Title>
        )}
        {renderMarketGroups(section.groups, selectedMap, isMobile, onToggleMarket, onToggleGroup, t)}
      </>
    )
  }

  return (
    <div>
      {sections.map((section, index) => (
        <div key={section.key}>
          {index > 0 && <Divider style={{ margin: '16px 0' }} />}
          <Title level={5} style={{ marginTop: index === 0 ? 0 : undefined, marginBottom: 12 }}>
            {section.title}
            <Text type="secondary" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
              ({section.groups.reduce((sum, g) => sum + g.markets.length, 0)})
            </Text>
          </Title>
          {renderMarketGroups(section.groups, selectedMap, isMobile, onToggleMarket, onToggleGroup, t)}
        </div>
      ))}
    </div>
  )
}

export default WhaleMonitorMarketGroupedList
