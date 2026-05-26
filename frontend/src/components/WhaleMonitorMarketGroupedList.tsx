import { useMemo } from 'react'
import { Checkbox, Collapse, Divider, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { groupMarketsBySection, type WhaleMonitorMarketGroup } from '../constants/whaleMonitor'
import type { WhaleMonitorMarketItem } from '../types'
import WhaleMonitorMarketListItem from './WhaleMonitorMarketListItem'

const { Text, Title } = Typography

interface WhaleMonitorMarketGroupedListProps {
  markets: WhaleMonitorMarketItem[]
  selectedMap: Map<string, WhaleMonitorMarketItem>
  isMobile: boolean
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void
  onToggleGroup: (markets: WhaleMonitorMarketItem[], checked: boolean) => void
}

const renderFlatMarketList = (
  markets: WhaleMonitorMarketItem[],
  selectedMap: Map<string, WhaleMonitorMarketItem>,
  isMobile: boolean,
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void,
  compact = false
) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: isMobile ? 4 : 8 }}>
    {markets.map(market => (
      <WhaleMonitorMarketListItem
        key={market.conditionId}
        market={market}
        checked={selectedMap.has(market.conditionId)}
        isMobile={isMobile}
        hideEventTitle
        compact={compact}
        onToggle={checked => onToggleMarket(market, checked)}
      />
    ))}
  </div>
)

const renderMarketGroups = (
  groups: WhaleMonitorMarketGroup[],
  selectedMap: Map<string, WhaleMonitorMarketItem>,
  isMobile: boolean,
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void,
  onToggleGroup: (markets: WhaleMonitorMarketItem[], checked: boolean) => void,
  t: (key: string, options?: Record<string, number>) => string,
  defaultActiveKeys?: string[]
) => {
  if (groups.length <= 1) {
    return renderFlatMarketList(
      groups.flatMap(g => g.markets),
      selectedMap,
      isMobile,
      onToggleMarket,
      groups.length === 1 && groups[0].key.startsWith('event:')
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
          <div style={{ flex: 1, minWidth: 0 }}>
            <Text strong style={{ wordBreak: 'break-word' }}>
              {group.title}
            </Text>
            <Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
              {t('whaleMonitorStrategy.marketSelect.marketsInGroup', { count: group.markets.length })}
            </Text>
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
      defaultActiveKey={defaultActiveKeys ?? groups.map(g => g.key)}
      items={collapseItems}
      style={{ background: 'transparent' }}
    />
  )
}

const renderSectionHeader = (sectionTitle: string, marketCount: number) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
    <Text strong style={{ fontSize: 16 }}>
      {sectionTitle}
    </Text>
    <Text type="secondary" style={{ fontSize: 13, fontWeight: 400 }}>
      ({marketCount})
    </Text>
  </div>
)

const renderSectionBody = (
  section: { key: 'game' | 'season'; groups: WhaleMonitorMarketGroup[] },
  selectedMap: Map<string, WhaleMonitorMarketItem>,
  isMobile: boolean,
  onToggleMarket: (market: WhaleMonitorMarketItem, checked: boolean) => void,
  onToggleGroup: (markets: WhaleMonitorMarketItem[], checked: boolean) => void,
  t: (key: string, options?: Record<string, number>) => string
) => {
  const isSeason = section.key === 'season'
  return renderMarketGroups(
    section.groups,
    selectedMap,
    isMobile,
    onToggleMarket,
    onToggleGroup,
    t,
    isSeason ? [] : section.groups.map(g => g.key)
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

  const totalGroups = sections.reduce((sum, s) => sum + s.groups.length, 0)
  const allMarkets = sections.flatMap(s => s.groups.flatMap(g => g.markets))

  if (totalGroups <= 1) {
    return renderFlatMarketList(allMarkets, selectedMap, isMobile, onToggleMarket)
  }

  if (sections.length === 1) {
    const section = sections[0]
    const marketCount = section.groups.reduce((sum, g) => sum + g.markets.length, 0)

    if (section.key === 'season' && section.groups.length > 1) {
      return (
        <Collapse
          bordered={false}
          defaultActiveKey={[]}
          style={{ background: 'transparent' }}
          items={[
            {
              key: 'season-section',
              label: renderSectionHeader(section.title, marketCount),
              children: renderSectionBody(
                section,
                selectedMap,
                isMobile,
                onToggleMarket,
                onToggleGroup,
                t
              )
            }
          ]}
        />
      )
    }

    return (
      <>
        <Title level={5} style={{ marginTop: 0, marginBottom: 12 }}>
          {section.title}
          <Text type="secondary" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
            ({marketCount})
          </Text>
        </Title>
        {renderSectionBody(section, selectedMap, isMobile, onToggleMarket, onToggleGroup, t)}
      </>
    )
  }

  return (
    <div>
      {sections.map((section, index) => {
        const marketCount = section.groups.reduce((sum, g) => sum + g.markets.length, 0)
        return (
          <div key={section.key}>
            {index > 0 && <Divider style={{ margin: '16px 0' }} />}
            {section.key === 'season' && section.groups.length > 1 ? (
              <Collapse
                bordered={false}
                defaultActiveKey={[]}
                style={{ background: 'transparent' }}
                items={[
                  {
                    key: 'season-section',
                    label: renderSectionHeader(section.title, marketCount),
                    children: renderSectionBody(
                      section,
                      selectedMap,
                      isMobile,
                      onToggleMarket,
                      onToggleGroup,
                      t
                    )
                  }
                ]}
              />
            ) : (
              <>
                <Title level={5} style={{ marginTop: index === 0 ? 0 : undefined, marginBottom: 12 }}>
                  {section.title}
                  <Text type="secondary" style={{ fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
                    ({marketCount})
                  </Text>
                </Title>
                {renderSectionBody(section, selectedMap, isMobile, onToggleMarket, onToggleGroup, t)}
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}

export default WhaleMonitorMarketGroupedList
