import type { WhaleMonitorFormDraft, WhaleMonitorMarketItem } from '../types'

export const WHALE_MONITOR_FORM_DRAFT_KEY = 'whale_monitor_form_draft'

export function saveWhaleMonitorFormDraft(draft: WhaleMonitorFormDraft): void {
  sessionStorage.setItem(WHALE_MONITOR_FORM_DRAFT_KEY, JSON.stringify(draft))
}

export function loadWhaleMonitorFormDraft(): WhaleMonitorFormDraft | null {
  const raw = sessionStorage.getItem(WHALE_MONITOR_FORM_DRAFT_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as WhaleMonitorFormDraft
  } catch {
    return null
  }
}

export function clearWhaleMonitorFormDraft(): void {
  sessionStorage.removeItem(WHALE_MONITOR_FORM_DRAFT_KEY)
}

export function conditionIdsToMarkets(
  conditionIds: string[],
  known: WhaleMonitorMarketItem[] = []
): WhaleMonitorMarketItem[] {
  return conditionIds.map(id => {
    const found = known.find(m => m.conditionId === id)
    return found ?? { conditionId: id, title: id }
  })
}

/** 解析 Gamma 返回的 outcomes JSON 字符串 */
export function parseMarketOutcomes(outcomes?: string): string[] {
  if (!outcomes?.trim()) return []
  try {
    const parsed = JSON.parse(outcomes) as unknown
    if (Array.isArray(parsed)) {
      return parsed.filter((item): item is string => typeof item === 'string' && item.length > 0)
    }
  } catch {
    return outcomes.split(',').map(s => s.trim()).filter(Boolean)
  }
  return []
}

export interface WhaleMonitorMarketGroup {
  key: string
  title: string
  markets: WhaleMonitorMarketItem[]
  imageUrl?: string
}

/** 市场行展示图：image > icon > eventImage */
export function pickMarketImageUrl(market?: WhaleMonitorMarketItem | null): string | undefined {
  if (!market) return undefined
  return market.image?.trim() || market.icon?.trim() || market.eventImage?.trim() || undefined
}

/** 赛事分组头图：优先 eventImage，否则取组内首个市场图 */
export function pickGroupImageUrl(markets: WhaleMonitorMarketItem[]): string | undefined {
  if (markets.length === 0) return undefined
  const eventImg = markets[0].eventImage?.trim()
  if (eventImg) return eventImg
  return pickMarketImageUrl(markets[0])
}

export interface WhaleMonitorMarketSection {
  key: 'game' | 'season'
  title: string
  groups: WhaleMonitorMarketGroup[]
}

export interface WhaleMonitorMarketSectionLabels {
  game: string
  season: string
  ungrouped: string
}

/** 先按单场/长期分块，再在块内按赛事或分类分组 */
export function groupMarketsBySection(
  markets: WhaleMonitorMarketItem[],
  labels: WhaleMonitorMarketSectionLabels
): WhaleMonitorMarketSection[] {
  if (markets.length === 0) return []

  const hasTyped = markets.some(m => m.marketType === 'game' || m.marketType === 'season')
  if (!hasTyped) {
    const groups = groupMarketsForDisplay(markets, labels.ungrouped)
    if (groups.length === 0) return []
    return [{ key: 'season', title: labels.ungrouped, groups }]
  }

  const sections: WhaleMonitorMarketSection[] = []
  const games = markets.filter(m => m.marketType === 'game')
  const seasons = markets.filter(m => m.marketType === 'season')

  if (games.length > 0) {
    sections.push({
      key: 'game',
      title: labels.game,
      groups: groupMarketsForDisplay(games, labels.ungrouped)
    })
  }
  if (seasons.length > 0) {
    sections.push({
      key: 'season',
      title: labels.season,
      groups: groupMarketsForDisplay(seasons, labels.ungrouped)
    })
  }
  return sections
}

/** 按赛事/分类分组展示市场列表 */
export function groupMarketsForDisplay(
  markets: WhaleMonitorMarketItem[],
  ungroupedLabel: string
): WhaleMonitorMarketGroup[] {
  if (markets.length === 0) return []

  const eventMap = new Map<string, WhaleMonitorMarketItem[]>()
  const miscMarkets: WhaleMonitorMarketItem[] = []

  for (const market of markets) {
    if (market.marketType === 'season') {
      miscMarkets.push(market)
      continue
    }
    const eventTitle = market.eventTitle?.trim()
    if (eventTitle) {
      const list = eventMap.get(eventTitle) ?? []
      list.push(market)
      eventMap.set(eventTitle, list)
    } else {
      miscMarkets.push(market)
    }
  }

  if (eventMap.size > 0) {
    const groups: WhaleMonitorMarketGroup[] = Array.from(eventMap.entries())
      .map(([title, items]) => ({
        key: `event:${title}`,
        title,
        markets: items,
        imageUrl: pickGroupImageUrl(items)
      }))
      .sort((a, b) => a.title.localeCompare(b.title))
    if (miscMarkets.length > 0) {
      groups.push({
        key: 'misc',
        title: ungroupedLabel,
        markets: miscMarkets,
        imageUrl: pickMarketImageUrl(miscMarkets[0])
      })
    }
    return groups
  }

  const categoryMap = new Map<string, WhaleMonitorMarketItem[]>()
  for (const market of markets) {
    const category = market.category?.trim() || ungroupedLabel
    const list = categoryMap.get(category) ?? []
    list.push(market)
    categoryMap.set(category, list)
  }

  if (categoryMap.size <= 1) {
    return [{ key: 'all', title: ungroupedLabel, markets }]
  }

  return Array.from(categoryMap.entries())
    .map(([title, items]) => ({
      key: `cat:${title}`,
      title,
      markets: items,
      imageUrl: pickMarketImageUrl(items[0])
    }))
    .sort((a, b) => a.title.localeCompare(b.title))
}

export function toWhaleMonitorMarketItem(m: {
  conditionId: string
  title: string
  slug?: string
  category?: string
  volume?: string
  outcomes?: string
  eventTitle?: string
  marketType?: 'game' | 'season'
  image?: string
  icon?: string
  eventImage?: string
}): WhaleMonitorMarketItem {
  return {
    conditionId: m.conditionId,
    title: m.title,
    slug: m.slug,
    category: m.category,
    volume: m.volume,
    outcomes: m.outcomes,
    eventTitle: m.eventTitle,
    marketType: m.marketType,
    image: m.image,
    icon: m.icon,
    eventImage: m.eventImage
  }
}
