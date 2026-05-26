import { useCallback, useEffect, useRef, useState } from 'react'
import { apiService } from '../services/api'

export interface GeoblockCheckResult {
  blocked: boolean
  ip: string
  country: string
  region: string
  checkedAt: number
  source: string
}

export type GeoblockCheckStatus = 'idle' | 'loading' | 'success' | 'error'

const CACHE_KEY = 'geoblock_check_cache'
const CACHE_TTL_MS = 5 * 60 * 1000

interface GeoblockCacheEntry {
  data: GeoblockCheckResult
  cachedAt: number
}

function readCache(): GeoblockCheckResult | null {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const entry = JSON.parse(raw) as GeoblockCacheEntry
    if (Date.now() - entry.cachedAt > CACHE_TTL_MS) {
      sessionStorage.removeItem(CACHE_KEY)
      return null
    }
    return entry.data
  } catch {
    return null
  }
}

function writeCache(data: GeoblockCheckResult): void {
  const entry: GeoblockCacheEntry = { data, cachedAt: Date.now() }
  sessionStorage.setItem(CACHE_KEY, JSON.stringify(entry))
}

export function useGeoblockCheck(autoFetch = true) {
  const [status, setStatus] = useState<GeoblockCheckStatus>('idle')
  const [data, setData] = useState<GeoblockCheckResult | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const fetchingRef = useRef(false)

  const fetchGeoblock = useCallback(async (force = false) => {
    if (fetchingRef.current) return
    if (!force) {
      const cached = readCache()
      if (cached) {
        setData(cached)
        setStatus('success')
        setErrorMessage(null)
        return
      }
    }
    fetchingRef.current = true
    setStatus('loading')
    setErrorMessage(null)
    try {
      const response = await apiService.proxyConfig.checkGeoblock()
      if (response.data.code === 0 && response.data.data) {
        const result: GeoblockCheckResult = {
          blocked: response.data.data.blocked,
          ip: response.data.data.ip,
          country: response.data.data.country,
          region: response.data.data.region,
          checkedAt: response.data.data.checkedAt,
          source: response.data.data.source ?? 'server'
        }
        writeCache(result)
        setData(result)
        setStatus('success')
      } else {
        setStatus('error')
        setErrorMessage(response.data.msg ?? 'Geoblock check failed')
        setData(null)
      }
    } catch (err: unknown) {
      setStatus('error')
      const message = err instanceof Error ? err.message : 'Geoblock check failed'
      setErrorMessage(message)
      setData(null)
    } finally {
      fetchingRef.current = false
    }
  }, [])

  useEffect(() => {
    if (autoFetch) {
      fetchGeoblock()
    }
  }, [autoFetch, fetchGeoblock])

  return {
    status,
    data,
    errorMessage,
    refresh: () => fetchGeoblock(true),
    loading: status === 'loading'
  }
}
