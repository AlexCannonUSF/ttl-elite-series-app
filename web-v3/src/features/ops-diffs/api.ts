import type { OpsSettlementDiffFocus, OpsSettlementDiffsResponse } from '@/features/ops-diffs/types'

export type FetchOpsDiffsOptions = {
  focus?: OpsSettlementDiffFocus
  page?: number
  size?: number
}

export async function fetchOpsDiffs(options: FetchOpsDiffsOptions = {}, signal?: AbortSignal): Promise<OpsSettlementDiffsResponse> {
  const params = new URLSearchParams()
  if (options.focus && options.focus !== 'ALL') {
    params.set('focus', options.focus)
  }
  if (typeof options.page === 'number' && options.page > 0) {
    params.set('page', String(options.page))
  }
  if (typeof options.size === 'number' && options.size > 0) {
    params.set('size', String(options.size))
  }

  const query = params.toString()
  const response = await fetch(`/api/v3/ops/diffs${query ? `?${query}` : ''}`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Ops settlement diffs request failed with ${response.status}`)
  }

  return (await response.json()) as OpsSettlementDiffsResponse
}
