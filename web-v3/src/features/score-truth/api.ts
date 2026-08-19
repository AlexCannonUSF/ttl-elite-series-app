import type {
  ScoreTruthEvidenceResponse,
  ScoreTruthReviewActionRequest,
  ScoreTruthReviewActionResponse,
  ScoreTruthReviewQueueResponse,
  SettlementReviewPageResponse,
} from '@/features/score-truth/types'

function isNumericId(id: string) {
  return /^\d+$/.test(id)
}

export async function fetchSettlementReview({
  page,
  size,
  suspiciousOnly,
  signal,
}: {
  page: number
  size: number
  suspiciousOnly: boolean
  signal?: AbortSignal
}): Promise<SettlementReviewPageResponse> {
  const query = new URLSearchParams()
  query.set('page', String(page))
  query.set('size', String(size))
  query.set('suspiciousOnly', String(suspiciousOnly))

  const response = await fetch(`/api/score-truth/settlement-review?${query}`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  await assertOk(response, 'Settlement review request')
  return (await response.json()) as SettlementReviewPageResponse
}

export async function fetchScoreTruthEvidence(id: string, signal?: AbortSignal): Promise<ScoreTruthEvidenceResponse> {
  const trimmedId = id.trim()
  const endpoint = isNumericId(trimmedId)
    ? `/api/score-truth/bets/${encodeURIComponent(trimmedId)}/evidence`
    : `/api/score-truth/evidence/${encodeURIComponent(trimmedId)}`

  const response = await fetch(endpoint, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`Score truth evidence request failed with ${response.status}`)
  }

  return (await response.json()) as ScoreTruthEvidenceResponse
}

export async function fetchScoreTruthReviewQueue({
  page,
  size,
  signal,
}: {
  page: number
  size: number
  signal?: AbortSignal
}): Promise<ScoreTruthReviewQueueResponse> {
  const query = new URLSearchParams()
  query.set('page', String(page))
  query.set('size', String(size))

  const response = await fetch(`/api/score-truth/review?${query}`, {
    headers: {
      Accept: 'application/json',
    },
    signal,
  })

  await assertOk(response, 'Score truth review queue request')
  return (await response.json()) as ScoreTruthReviewQueueResponse
}

export async function submitScoreTruthReviewAction(
  decisionId: number,
  request: ScoreTruthReviewActionRequest,
): Promise<ScoreTruthReviewActionResponse> {
  const response = await fetch(`/api/score-truth/review/${encodeURIComponent(String(decisionId))}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })

  await assertOk(response, 'Score truth review action request')
  return (await response.json()) as ScoreTruthReviewActionResponse
}

async function assertOk(response: Response, label: string) {
  if (response.ok) {
    return
  }

  let detail = ''
  try {
    const body = (await response.json()) as { message?: string }
    detail = body.message ? `: ${body.message}` : ''
  } catch {
    detail = ''
  }
  throw new Error(`${label} failed with ${response.status}${detail}`)
}
