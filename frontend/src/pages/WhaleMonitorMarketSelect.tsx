import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import {
  Button,
  Card,
  Empty,
  Input,
  Select,
  Segmented,
  Spin,
  Tag,
  Typography
} from 'antd'
import { ArrowLeftOutlined, SearchOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'
import WhaleMonitorMarketGroupedList from '../components/WhaleMonitorMarketGroupedList'
import WhaleMonitorMarketThumbnail from '../components/WhaleMonitorMarketThumbnail'
import { toWhaleMonitorMarketItem } from '../constants/whaleMonitor'
import type { WhaleMonitorMarketItem, WhaleMonitorMarketSelectLocationState } from '../types'

const { Title, Text } = Typography

const TAG_OPTIONS = [
  { key: 'all', value: '' },
  { key: 'sports', value: '1' },
  { key: 'politics', value: '2' },
  { key: 'crypto', value: '21' },
  { key: 'popCulture', value: '100639' }
] as const

const WhaleMonitorMarketSelect: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  const locationState = (location.state as WhaleMonitorMarketSelectLocationState | null) ?? null
  const initialSelected = locationState?.selectedMarkets ?? []

  const [keyword, setKeyword] = useState('')
  const [debouncedKeyword, setDebouncedKeyword] = useState('')
  const [tagId, setTagId] = useState<string>('')
  const [sportSubSeriesId, setSportSubSeriesId] = useState<string | undefined>(undefined)
  const [sportSubCategories, setSportSubCategories] = useState<
    Array<{ id: number; slug: string; label: string; tagId: string; seriesId: string; image?: string }>
  >([])
  const [marketList, setMarketList] = useState<WhaleMonitorMarketItem[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedMap, setSelectedMap] = useState<Map<string, WhaleMonitorMarketItem>>(() => {
    const map = new Map<string, WhaleMonitorMarketItem>()
    initialSelected.forEach(m => map.set(m.conditionId, m))
    return map
  })

  const tagSegmentOptions = useMemo(
    () =>
      TAG_OPTIONS.map(opt => ({
        label: t(`whaleMonitorStrategy.marketSelect.tag.${opt.key}`),
        value: opt.value
      })),
    [t]
  )

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedKeyword(keyword.trim()), 400)
    return () => clearTimeout(timer)
  }, [keyword])

  useEffect(() => {
    if (tagId === '1') {
      fetchSportSubCategories()
      setSportSubSeriesId(undefined)
    } else {
      setSportSubCategories([])
      setSportSubSeriesId(undefined)
    }
  }, [tagId])

  const fetchSportSubCategories = async () => {
    try {
      const res = await apiService.markets.sportsCategories()
      if (res.data.code === 0 && res.data.data) {
        setSportSubCategories(
          res.data.data.filter((s): s is typeof s & { seriesId: string } => Boolean(s.seriesId))
        )
      } else {
        setSportSubCategories([])
      }
    } catch {
      setSportSubCategories([])
    }
  }

  const fetchMarkets = useCallback(async () => {
    const selectedSport = sportSubCategories.find(s => s.seriesId === sportSubSeriesId)
    const seriesId = sportSubSeriesId
    const sportSlug = selectedSport?.slug
    const effectiveTagId = tagId && tagId !== '1' ? tagId : undefined
    const searchKeyword = debouncedKeyword

    if (tagId === '1' && !sportSubSeriesId && searchKeyword.length < 2) {
      setMarketList([])
      return
    }
    if (!seriesId && !effectiveTagId && searchKeyword.length < 2) {
      setMarketList([])
      return
    }

    setLoading(true)
    try {
      const res = await apiService.markets.search({
        keyword: searchKeyword.length >= 2 ? searchKeyword : '',
        seriesId: seriesId || undefined,
        sportSlug: seriesId ? sportSlug : undefined,
        tagId: seriesId ? undefined : effectiveTagId,
        limit: 200
      })
      if (res.data.code === 0 && res.data.data) {
        setMarketList(res.data.data.map(m => toWhaleMonitorMarketItem(m)))
      } else {
        setMarketList([])
      }
    } catch {
      setMarketList([])
    } finally {
      setLoading(false)
    }
  }, [debouncedKeyword, tagId, sportSubSeriesId, sportSubCategories])

  useEffect(() => {
    fetchMarkets()
  }, [fetchMarkets])

  const selectedList = useMemo(() => Array.from(selectedMap.values()), [selectedMap])

  const toggleMarket = (market: WhaleMonitorMarketItem, checked: boolean) => {
    setSelectedMap(prev => {
      const next = new Map(prev)
      if (checked) {
        next.set(market.conditionId, market)
      } else {
        next.delete(market.conditionId)
      }
      return next
    })
  }

  const toggleGroupMarkets = (markets: WhaleMonitorMarketItem[], checked: boolean) => {
    setSelectedMap(prev => {
      const next = new Map(prev)
      for (const market of markets) {
        if (checked) {
          next.set(market.conditionId, market)
        } else {
          next.delete(market.conditionId)
        }
      }
      return next
    })
  }

  const handleConfirm = () => {
    navigate('/whale-monitor-strategy', {
      state: { selectedMarkets: selectedList }
    })
  }

  const handleBack = () => {
    navigate('/whale-monitor-strategy')
  }

  const showSelectSportHint = tagId === '1' && !sportSubSeriesId && debouncedKeyword.length < 2
  const showSportsGamesEmpty =
    tagId === '1' && !!sportSubSeriesId && marketList.length === 0 && !loading && debouncedKeyword.length < 2
  const showSearchHint = !tagId && debouncedKeyword.length < 2

  return (
    <div style={{ padding: isMobile ? 12 : 24, paddingBottom: isMobile ? 88 : 24 }}>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={handleBack}
          style={{ marginBottom: 12, minHeight: 44 }}
        >
          {t('whaleMonitorStrategy.marketSelect.back')}
        </Button>
        <Title level={isMobile ? 4 : 3} style={{ margin: 0 }}>
          {t('whaleMonitorStrategy.marketSelect.title')}
        </Title>
        <Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
          {t('whaleMonitorStrategy.marketSelect.subtitle')}
        </Text>
      </div>

      <Card size="small" style={{ marginBottom: 12 }}>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder={t('whaleMonitorStrategy.marketSelect.searchPlaceholder')}
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          size={isMobile ? 'large' : 'middle'}
          style={{ marginBottom: 12 }}
        />
        <Segmented
          block={isMobile}
          options={tagSegmentOptions}
          value={tagId}
          onChange={val => setTagId(val as string)}
          style={{ marginBottom: tagId === '1' ? 12 : 0 }}
        />
        {tagId === '1' && sportSubCategories.length > 0 && (
          <>
            <Select
              allowClear
              showSearch
              placeholder={t('whaleMonitorStrategy.form.sportLeague')}
              style={{ width: '100%', marginTop: 12 }}
              value={sportSubSeriesId}
              onChange={setSportSubSeriesId}
              optionFilterProp="label"
              size={isMobile ? 'large' : 'middle'}
              options={sportSubCategories.map(s => ({
                label: s.label,
                value: s.seriesId,
                image: s.image
              }))}
              optionRender={option => (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <WhaleMonitorMarketThumbnail src={option.data.image as string | undefined} size={28} />
                  <span>{option.label}</span>
                </div>
              )}
              labelRender={props => {
                const sport = sportSubCategories.find(s => s.seriesId === props.value)
                if (!sport) return props.label
                return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <WhaleMonitorMarketThumbnail src={sport.image} size={22} />
                    <span>{sport.label}</span>
                  </div>
                )
              }}
            />
            {sportSubSeriesId && (
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
                {t('whaleMonitorStrategy.marketSelect.sportsGamesHint')}
              </Text>
            )}
          </>
        )}
      </Card>

      {selectedList.length > 0 && (
        <Card size="small" title={t('whaleMonitorStrategy.marketSelect.selectedTitle', { count: selectedList.length })} style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {selectedList.map(m => (
              <Tag
                key={m.conditionId}
                closable
                onClose={() => toggleMarket(m, false)}
                style={{ margin: 0, maxWidth: '100%' }}
              >
                <span style={{ wordBreak: 'break-all' }}>{m.title}</span>
              </Tag>
            ))}
          </div>
        </Card>
      )}

      <Card size="small" bodyStyle={{ padding: isMobile ? 8 : 16 }}>
        <Spin spinning={loading}>
          {showSearchHint ? (
            <Empty description={t('whaleMonitorStrategy.marketSelect.searchHint')} />
          ) : showSelectSportHint ? (
            <Empty description={t('whaleMonitorStrategy.form.selectSportFirst')} />
          ) : showSportsGamesEmpty ? (
            <Empty description={t('whaleMonitorStrategy.marketSelect.sportsGamesEmpty')} />
          ) : marketList.length === 0 && !loading ? (
            <Empty description={t('whaleMonitorStrategy.marketSelect.empty')} />
          ) : (
            <WhaleMonitorMarketGroupedList
              markets={marketList}
              selectedMap={selectedMap}
              isMobile={isMobile}
              onToggleMarket={toggleMarket}
              onToggleGroup={toggleGroupMarkets}
            />
          )}
        </Spin>
      </Card>

      <div
        style={{
          position: isMobile ? 'fixed' : 'sticky',
          bottom: 0,
          left: 0,
          right: 0,
          padding: isMobile ? '12px 16px' : '16px 0 0',
          background: isMobile ? '#fff' : 'transparent',
          borderTop: isMobile ? '1px solid #f0f0f0' : undefined,
          zIndex: 10,
          display: 'flex',
          gap: 12,
          alignItems: 'center',
          justifyContent: 'flex-end'
        }}
      >
        {!isMobile && (
          <Text type="secondary">
            {t('whaleMonitorStrategy.marketSelect.selectedCount', { count: selectedList.length })}
          </Text>
        )}
        <Button onClick={handleBack} style={{ minHeight: 44 }}>
          {t('common.cancel')}
        </Button>
        <Button type="primary" onClick={handleConfirm} style={{ minHeight: 44 }}>
          {isMobile
            ? t('whaleMonitorStrategy.marketSelect.confirmWithCount', { count: selectedList.length })
            : t('whaleMonitorStrategy.marketSelect.confirm')}
        </Button>
      </div>
    </div>
  )
}

export default WhaleMonitorMarketSelect
