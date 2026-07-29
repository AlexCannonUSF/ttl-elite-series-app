import { type ReactNode, useEffect, useRef, useState } from 'react'

import { cn } from '@/lib/utils'

type FlashDirection = 'down' | 'flat' | 'up'

type FlashOnChangeProps = {
  children: ReactNode
  className?: string
  value: number | string | null | undefined
}

export function FlashOnChange({ children, className, value }: FlashOnChangeProps) {
  const previousRef = useRef(value)
  const [direction, setDirection] = useState<FlashDirection>('flat')

  useEffect(() => {
    const previous = previousRef.current
    if (previous === value) {
      return
    }

    previousRef.current = value
    setDirection(compare(previous, value))
    const timeout = window.setTimeout(() => setDirection('flat'), 850)
    return () => window.clearTimeout(timeout)
  }, [value])

  return (
    <span
      className={cn(
        'inline-flex min-w-0 items-center justify-end rounded-lg px-2 py-1 transition-colors duration-300',
        direction === 'up' && 'bg-emerald-100 text-emerald-800 ring-1 ring-emerald-200',
        direction === 'down' && 'bg-rose-100 text-rose-800 ring-1 ring-rose-200',
        direction === 'flat' && 'bg-transparent',
        className,
      )}
    >
      {children}
    </span>
  )
}

function compare(previous: number | string | null | undefined, value: number | string | null | undefined): FlashDirection {
  if (typeof previous === 'number' && typeof value === 'number') {
    if (!Number.isFinite(previous) || !Number.isFinite(value)) {
      return 'up'
    }
    if (value > previous) {
      return 'up'
    }
    if (value < previous) {
      return 'down'
    }
  }
  return 'up'
}
