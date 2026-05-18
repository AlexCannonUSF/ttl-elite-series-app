import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full text-sm font-semibold transition-all outline-none disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary:
          'bg-[var(--accent-strong)] px-4 py-2.5 text-[var(--ink-strong)] shadow-[0_18px_48px_-24px_var(--accent-glow)] hover:translate-y-[-1px] hover:bg-[var(--accent-bright)]',
        secondary:
          'border border-[var(--line-strong)] bg-[var(--panel)] px-4 py-2.5 text-[var(--ink)] hover:border-[var(--accent-soft)] hover:text-[var(--ink-strong)]',
        ghost:
          'px-3 py-2 text-[var(--ink-muted)] hover:bg-[var(--panel-soft)] hover:text-[var(--ink-strong)]',
      },
      size: {
        default: '',
        sm: 'px-3 py-2 text-xs',
        lg: 'px-5 py-3 text-sm',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'default',
    },
  },
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

export function Button({ className, variant, size, asChild = false, ...props }: ButtonProps) {
  const Comp = asChild ? Slot : 'button'

  return <Comp className={cn(buttonVariants({ variant, size }), className)} {...props} />
}
