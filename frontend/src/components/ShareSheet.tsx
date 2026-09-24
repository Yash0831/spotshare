import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { ApiError, AvailabilityWindow, SessionExpiredError } from '../api/types';
import { formatRate } from '../utils/money';
import { formatTime } from '../utils/time';

const QUICK_HOURS = [1, 2, 4] as const;
type Chip = 1 | 2 | 4 | 'custom';
const MIN_SHARE_MS = 30 * 60 * 1000;
const MAX_DOLLARS = 100;

interface ShareSheetProps {
  spaceId: string;
  spaceLabel: string;
  onClose: () => void;
  onShared: (window: AvailabilityWindow) => void;
  onSessionExpired: () => void;
}

/** Local "YYYY-MM-DDTHH:MM" for datetime-local inputs. */
function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/**
 * The signature "I'm leaving / Share My Spot" sheet: pick a return time and
 * a price (free or hourly), one tap, live in under a minute. Big touch
 * targets for use beside the car.
 */
export default function ShareSheet({
  spaceId,
  spaceLabel,
  onClose,
  onShared,
  onSessionExpired,
}: ShareSheetProps) {
  const [chip, setChip] = useState<Chip>(2);
  const [customValue, setCustomValue] = useState('');
  const [priceMode, setPriceMode] = useState<'free' | 'hourly'>('free');
  const [dollars, setDollars] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [sharing, setSharing] = useState(false);

  const minInput = useMemo(() => toLocalInputValue(new Date(Date.now() + MIN_SHARE_MS)), []);

  /** The effective return time, or null when the custom input is invalid. */
  function returnTime(): Date | null {
    if (chip === 'custom') {
      const d = new Date(customValue);
      return Number.isNaN(d.getTime()) ? null : d;
    }
    return new Date(Date.now() + chip * 3600 * 1000);
  }

  const rt = returnTime();
  const previewRate = priceMode === 'free' ? null : Math.round(Number(dollars) * 100);

  function validate(): string | null {
    const t = returnTime();
    if (!t) return 'Choose a valid return time.';
    if (t.getTime() <= Date.now()) return 'Your return time must be in the future.';
    if (t.getTime() - Date.now() < MIN_SHARE_MS)
      return 'Shares need to be at least 30 minutes long.';
    if (priceMode === 'hourly') {
      const amount = Number(dollars);
      if (dollars.trim() === '' || Number.isNaN(amount) || amount <= 0)
        return 'Enter an hourly price above $0, or choose Free.';
      if (amount > MAX_DOLLARS) return 'The hourly price can\u2019t be more than $100.';
    }
    return null;
  }

  async function confirm() {
    if (sharing) return;
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    const t = returnTime();
    if (!t) {
      setError('Choose a valid return time.');
      return;
    }
    setSharing(true);
    setError(null);
    try {
      const window = await api.availability.share(spaceId, {
        returnTime: t.toISOString(),
        hourlyRateCents: priceMode === 'free' ? null : Math.round(Number(dollars) * 100),
      });
      onShared(window);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        onSessionExpired();
        return;
      }
      setError(
        e instanceof ApiError
          ? e.message
          : 'Could not share your spot. Please try again.',
      );
    } finally {
      setSharing(false);
    }
  }

  const chipCls = (active: boolean) =>
    `flex-1 rounded-lg border px-3 py-3 text-base font-semibold ${
      active
        ? 'border-sky-700 bg-sky-50 text-sky-800'
        : 'border-slate-300 bg-white text-slate-700'
    }`;

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Share my spot"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-t-2xl bg-white p-6 pb-8 shadow-xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mx-auto mb-4 h-1 w-10 rounded-full bg-slate-300" aria-hidden="true" />
        <h2 className="text-xl font-bold text-slate-900">Share my spot</h2>
        <p className="mt-1 text-sm text-slate-600">
          {spaceLabel} — it stays listed until your return time, then disappears automatically.
        </p>

        {error && (
          <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}

        <div className="mt-5">
          <p className="mb-2 text-sm font-semibold text-slate-700">I&rsquo;ll be back in…</p>
          <div className="flex gap-2" role="group" aria-label="Return time">
            {QUICK_HOURS.map((h) => (
              <button
                key={h}
                type="button"
                aria-pressed={chip === h}
                onClick={() => setChip(h)}
                className={chipCls(chip === h)}
              >
                {h}h
              </button>
            ))}
            <button
              type="button"
              aria-pressed={chip === 'custom'}
              onClick={() => setChip('custom')}
              className={chipCls(chip === 'custom')}
            >
              Custom
            </button>
          </div>
          {chip === 'custom' && (
            <input
              type="datetime-local"
              aria-label="Custom return time"
              min={minInput}
              value={customValue}
              onChange={(e) => setCustomValue(e.target.value)}
              className="mt-3 w-full rounded-lg border border-slate-300 bg-white px-4 py-3 text-base text-slate-900 focus:border-sky-600 focus:outline-none"
            />
          )}
        </div>

        <div className="mt-5">
          <p className="mb-2 text-sm font-semibold text-slate-700">Price</p>
          <div className="flex gap-2" role="group" aria-label="Price">
            <button
              type="button"
              aria-pressed={priceMode === 'free'}
              onClick={() => setPriceMode('free')}
              className={chipCls(priceMode === 'free')}
            >
              Free
            </button>
            <button
              type="button"
              aria-pressed={priceMode === 'hourly'}
              onClick={() => setPriceMode('hourly')}
              className={chipCls(priceMode === 'hourly')}
            >
              Hourly
            </button>
          </div>
          {priceMode === 'hourly' && (
            <div className="relative mt-3">
              <span className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-base text-slate-500">
                $
              </span>
              <input
                type="number"
                inputMode="decimal"
                min="0.01"
                max="100"
                step="0.01"
                aria-label="Hourly price in dollars"
                placeholder="3.00"
                value={dollars}
                onChange={(e) => setDollars(e.target.value)}
                className="w-full rounded-lg border border-slate-300 bg-white py-3 pl-8 pr-4 text-base text-slate-900 focus:border-sky-600 focus:outline-none"
              />
            </div>
          )}
        </div>

        {rt && (
          <p className="mt-5 rounded-lg bg-slate-100 px-4 py-3 text-sm text-slate-700" aria-live="polite">
            Available until <strong>{formatTime(rt.toISOString())}</strong> ·{' '}
            <strong>{formatRate(previewRate)}</strong>
          </p>
        )}

        <div className="mt-5 flex gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={sharing}
            className="rounded-lg border border-slate-300 px-5 py-3 text-base font-semibold text-slate-700 disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void confirm()}
            disabled={sharing}
            className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-bold text-white shadow-sm disabled:opacity-60"
          >
            {sharing ? 'Sharing…' : 'Share my spot'}
          </button>
        </div>
      </div>
    </div>
  );
}
