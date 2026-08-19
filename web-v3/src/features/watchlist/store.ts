import { useCallback, useEffect, useState } from 'react'

const STORAGE_KEY = 'ttlelite.user.watchlist.v1'
const CHANGE_EVENT = 'ttlelite-watchlist-change'

export type WatchlistItem = {
  id: string
  kind: 'MATCH' | 'PLAYER'
  label: string
  detail: string | null
  href: string
  addedAt: string
}

function read(): WatchlistItem[] {
  try {
    const value = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? '[]') as unknown
    if (!Array.isArray(value)) return []
    return value.filter((item): item is WatchlistItem => Boolean(
      item && typeof item === 'object'
      && typeof (item as WatchlistItem).id === 'string'
      && ((item as WatchlistItem).kind === 'MATCH' || (item as WatchlistItem).kind === 'PLAYER')
      && typeof (item as WatchlistItem).label === 'string'
      && typeof (item as WatchlistItem).href === 'string',
    ))
  } catch {
    return []
  }
}

function write(items: WatchlistItem[]) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  window.dispatchEvent(new Event(CHANGE_EVENT))
}

export function useWatchlist() {
  const [items, setItems] = useState<WatchlistItem[]>(read)
  useEffect(() => {
    const refresh = () => setItems(read())
    window.addEventListener('storage', refresh)
    window.addEventListener(CHANGE_EVENT, refresh)
    return () => {
      window.removeEventListener('storage', refresh)
      window.removeEventListener(CHANGE_EVENT, refresh)
    }
  }, [])
  const toggle = useCallback((item: Omit<WatchlistItem, 'addedAt'>) => {
    const current = read()
    const exists = current.some((entry) => entry.kind === item.kind && entry.id === item.id)
    write(exists
      ? current.filter((entry) => entry.kind !== item.kind || entry.id !== item.id)
      : [{ ...item, addedAt: new Date().toISOString() }, ...current].slice(0, 200))
  }, [])
  const remove = useCallback((kind: WatchlistItem['kind'], id: string) => {
    write(read().filter((entry) => entry.kind !== kind || entry.id !== id))
  }, [])
  return { items, toggle, remove }
}
