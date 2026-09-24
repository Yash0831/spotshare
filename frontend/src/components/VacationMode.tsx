import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { ApiError, SessionExpiredError, VacationPayload } from '../api/types';
import { formatTripLength } from '../utils/time';

const MAX_DOLLARS = 100;
const MIN_MINUTES = 30;

/** Local "YYYY-MM-DDTHH:MM" for datetime-local inputs. */
function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

interface VacationModeProps {
  spaceId: string;
  /** Refresh the parent's window list — the vacation window appears right away. */
  onWindowsChanged: () => void;
  onSessionExpired: () => void;
}

/**
 * Vacation mode: the host shares their spot for a whole trip — a window
 * spanning multiple days, e.g. Friday 6:00 PM → Monday 9:00 AM. Unlike the
 * one-tap share (which always starts now), the host picks both the start and
 * the end. The server creates one VACATION window; drivers reserve inside it
 * like any other window, and ending the trip early is the return-early flow.
 */
export default function VacationMode({
  spaceId,
  onWindowsChanged,
  onSessionExpired,
}: VacationModeProps) {
  const [showForm, setShowForm] = useState(false);
  const [startValue, setStartValue] = useState('');
  const [endValue, setEndValue] = useState('');
  const [priceMode, setPriceMode] = useState<'free' | 'hourly'>('free');
  const [dollars, setDollars] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const minInput = useMemo(() => toLocalInputValue(new Date()), []);

  const parsed = useMemo(() => {
    const start = startValue ? new Date(startValue) : null;
    const end = endValue ? new Date(endValue) : null;
    const validStart = start && !Number.isNaN(start.getTime()) ? start : null;
    const validEnd = end && !Number.isNaN(end.getTime()) ? end : null;
    return { start: validStart, end: validEnd };
  }, [startValue, endValue]);

  const tripMs =
    parsed.start && parsed.end ? parsed.end.getTime() - parsed.start.getTime() : null;

  function validate(): string | null {
    if (!parsed.start) return 'Choose when your vacation starts.';
    if (!parsed.end) return 'Choose when your vacation ends.';
    if (parsed.start.getTime() <= Date.now() - 2 * 60 * 1000)
      return 'Your vacation start must be in the future.';
    if (parsed.end.getTime() <= parsed.start.getTime())
      return 'Your vacation end must be after your start.';
    if (tripMs !== null && tripMs < MIN_MINUTES * 60 * 1000)
      return 'Shares need to be at least 30 minutes long.';
    if (priceMode === 'hourly') {
      const amount = Number(dollars);
      if (dollars.trim() === '' || Number.isNaN(amount) || amount <= 0)
        return 'Enter an hourly price above $0, or choose Free.';
      if (amount > MAX_DOLLARS) return 'The hourly price can\u2019t be more than $100.';
    }
    return null;
  }

  async function create() {
    if (saving) return;
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    const { start, end } = parsed;
    if (!start || !end) {
      setError('Choose a start and an end for your trip.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const payload: VacationPayload = {
        startDateTime: start.toISOString(),
        endDateTime: end.toISOString(),
        hourlyRateCents:
          priceMode === 'free' ? null : Math.round(Number(dollars) * 100),
      };
      await api.availability.vacation(spaceId, payload);
      setShowForm(false);
      setStartValue('');
      setEndValue('');
      setDollars('');
      onWindowsChanged();
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        onSessionExpired();
        return;
      }
      setError(
        e instanceof ApiError
          ? e.message
          : 'Could not start vacation mode. Please try again.',
      );
    } finally {
      setSaving(false);
    }
  }

  const inputCls =
    'w-full rounded-lg border border-slate-300 bg-white px-4 py-3 text-base text-slate-900 focus:border-sky-600 focus:outline-none';

  if (!showForm) {
    return (
      <button
        type="button"
        onClick={() => setShowForm(true)}
        className="mt-3 w-full rounded-xl border border-dashed border-slate-300 bg-white px-4 py-3 text-sm font-semibold text-sky-700"
      >
        + Going on vacation? Share for the whole trip
      </button>
    );
  }

  return (
    <div className="mt-3 rounded-xl border border-slate-200 bg-white p-4">
      <h3 className="text-base font-bold text-slate-900">Vacation mode</h3>
      <p className="mt-1 text-sm text-slate-600">
        Away for a few days? Share your spot for the whole trip. Drivers can reserve parts of
        it, and you can end the trip early anytime — a parked driver is never cut off.
      </p>

      {error && (
        <div
          role="alert"
          className="mt-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
        >
          {error}
        </div>
      )}

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <div>
          <label className="mb-1 block text-sm font-semibold text-slate-700" htmlFor="vac-start">
            Leaving
          </label>
          <input
            id="vac-start"
            type="datetime-local"
            aria-label="Vacation start"
            min={minInput}
            value={startValue}
            onChange={(e) => setStartValue(e.target.value)}
            className={inputCls}
          />
        </div>
        <div>
          <label className="mb-1 block text-sm font-semibold text-slate-700" htmlFor="vac-end">
            Back
          </label>
          <input
            id="vac-end"
            type="datetime-local"
            aria-label="Vacation end"
            min={minInput}
            value={endValue}
            onChange={(e) => setEndValue(e.target.value)}
            className={inputCls}
          />
        </div>
      </div>

      {tripMs !== null && tripMs > 0 && (
        <p className="mt-3 rounded-lg bg-slate-100 px-4 py-3 text-sm text-slate-700" aria-live="polite">
          Your spot is shared for <strong>{formatTripLength(tripMs)}</strong>.
        </p>
      )}

      <fieldset className="mt-3">
        <legend className="mb-1 text-sm font-semibold text-slate-700">Price</legend>
        <div className="flex gap-2">
          {(['free', 'hourly'] as const).map((mode) => (
            <button
              key={mode}
              type="button"
              aria-pressed={priceMode === mode}
              onClick={() => setPriceMode(mode)}
              className={`flex-1 rounded-lg border px-3 py-2.5 text-sm font-semibold ${
                priceMode === mode
                  ? 'border-sky-700 bg-sky-50 text-sky-800'
                  : 'border-slate-300 bg-white text-slate-700'
              }`}
            >
              {mode === 'free' ? 'Free' : 'Hourly'}
            </button>
          ))}
        </div>
        {priceMode === 'hourly' && (
          <div className="relative mt-2">
            <span className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-slate-500">
              $
            </span>
            <input
              type="number"
              min="0.01"
              max="100"
              step="0.01"
              aria-label="Hourly price in dollars"
              placeholder="5.00"
              value={dollars}
              onChange={(e) => setDollars(e.target.value)}
              className={`${inputCls} pl-8`}
            />
          </div>
        )}
      </fieldset>

      <div className="mt-4 flex gap-3">
        <button
          type="button"
          onClick={() => {
            setShowForm(false);
            setError(null);
          }}
          disabled={saving}
          className="rounded-lg border border-slate-300 px-5 py-3 text-base font-semibold text-slate-700 disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={() => void create()}
          disabled={saving}
          className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-bold text-white shadow-sm disabled:opacity-60"
        >
          {saving ? 'Sharing…' : 'Share for the trip'}
        </button>
      </div>
    </div>
  );
}
