import { useEffect, useState } from 'react';

import { formatLongRemaining } from '../utils/time';

export interface Countdown {
  /** Milliseconds remaining until the end instant, clamped at 0. */
  remainingMs: number;
  /** "1 hr 42 min" style label, updated live. */
  label: string;
  /** True once the end instant has passed. */
  isOver: boolean;
  /**
   * True when more than 0 and at most 15 minutes remain — the in-app
   * reminder threshold (spec §2). In-app only: V1 has no push
   * notifications or SMS, so this banner is the reminder.
   */
  isFinalStretch: boolean;
}

const FIFTEEN_MINUTES_MS = 15 * 60 * 1000;

/**
 * A live countdown to an ISO end instant. Ticks once a second; the tick is
 * derived from `Date.now()`, so a backgrounded tab self-corrects when it
 * regains focus instead of drifting.
 */
export function useCountdown(endIso: string): Countdown {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const remainingMs = Math.max(0, Date.parse(endIso) - now);
  return {
    remainingMs,
    label: formatLongRemaining(remainingMs),
    isOver: remainingMs <= 0,
    isFinalStretch: remainingMs > 0 && remainingMs <= FIFTEEN_MINUTES_MS,
  };
}
