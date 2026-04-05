export function asPct(value: number, digits = 1): string {
  return `${(value * 100).toFixed(digits)}%`
}

export function asSigned(value: number, digits = 2): string {
  const fixed = value.toFixed(digits)
  return value > 0 ? `+${fixed}` : fixed
}

const LOCAL_DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const LOCAL_DATE_TIME_RE =
  /^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?(?:Z|[+-]\d{2}:\d{2})?$/
const HAS_ZONE_RE = /(Z|[+-]\d{2}:\d{2})$/

function parseLocalDateValue(value: string | null | undefined): Date | null {
  if (!value) return null
  const raw = value.trim()
  if (!raw) return null

  const dateOnly = LOCAL_DATE_ONLY_RE.exec(raw)
  if (dateOnly) {
    return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]))
  }

  const normalized = raw.includes(' ') && !raw.includes('T') ? raw.replace(' ', 'T') : raw
  const match = LOCAL_DATE_TIME_RE.exec(normalized)
  if (match) {
    const withZone = HAS_ZONE_RE.test(normalized)
    if (withZone) {
      const parsed = new Date(normalized)
      return Number.isNaN(parsed.getTime()) ? null : parsed
    }
    const year = Number(match[1])
    const month = Number(match[2]) - 1
    const day = Number(match[3])
    const hour = Number(match[4] ?? '0')
    const minute = Number(match[5] ?? '0')
    const second = Number(match[6] ?? '0')
    const milli = Number((match[7] ?? '0').slice(0, 3).padEnd(3, '0'))
    return new Date(year, month, day, hour, minute, second, milli)
  }

  const fallback = new Date(normalized)
  return Number.isNaN(fallback.getTime()) ? null : fallback
}

export function toEpochMillis(value: string | null | undefined): number {
  const parsed = parseLocalDateValue(value)
  return parsed ? parsed.getTime() : Number.NaN
}

export function asLocalDate(
  value: string | null,
  opts?: {
    includeTime?: boolean
    fallback?: string
  }
): string {
  const raw = value?.trim() ?? ''
  const parsed = parseLocalDateValue(value)
  if (!parsed) return opts?.fallback ?? (value?.trim() || 'N/A')
  const autoIncludeTime = !LOCAL_DATE_ONLY_RE.test(raw)
  const includeTime = opts?.includeTime ?? autoIncludeTime
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    ...(includeTime ? { timeStyle: 'short' } : {}),
  }).format(parsed)
}

export function asDateOnly(value: string | null, fallback = 'N/A'): string {
  return asLocalDate(value, { includeTime: false, fallback })
}

export function asDurationSeconds(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '0s'
  const total = Math.max(0, Math.round(value))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  if (minutes <= 0) return `${seconds}s`
  return `${minutes}m ${seconds}s`
}
