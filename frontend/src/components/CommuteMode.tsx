import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import { ApiError, CommuteSchedule, SessionExpiredError } from '../api/types';
import { formatRate } from '../utils/money';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
const WEEKDAY_FULL = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
] as const;
const MAX_DOLLARS = 100;
const MIN_MINUTES = 30;

/** The host's device timezone — sent with the schedule so windows land at the right local hour. */
function deviceTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** "09:00" -> "9:00 AM". */
export function formatTimeOfDay(hhmm: string): string {
  const [hRaw, mRaw] = hhmm.split(':');
  const h = Number(hRaw);
  const m = Number(mRaw ?? '0');
  if (Number.isNaN(h) || Number.isNaN(m)) return hhmm;
  const ampm = h >= 12 ? 'PM' : 'AM';
  const hr = h % 12 === 0 ? 12 : h % 12;
  return `${hr}:${String(m).padStart(2, '0')} ${ampm}`;
}

/** Next calendar date matching the weekday (0 = Monday .. 6 = Sunday). */
function nextOccurrence(dayOfWeek: number): Date {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  for (let i = 0; i < 8; i++) {
    const jsDay = (d.getDay() + 6) % 7; // JS: 0=Sunday -> 0=Monday
    if (jsDay === dayOfWeek) return d;
    d.setDate(d.getDate() + 1);
  }
  return d;
}

function formatShortDate(d: Date): string {
  return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
}

interface CommuteModeProps {
  spaceId: string;
  /** Refresh the parent's window list — schedules materialize windows immediately. */
  onWindowsChanged: () => void;
  onSessionExpired: () => void;
}

/**
 * Commute mode: the host sets a weekly pattern once ("weekdays 9:00 AM –
 * 5:00 PM") and the server materializes real availability windows two weeks
 * out. Pausing stops future materialization and removes future unreserved
 * commute windows; windows with a confirmed reservation are never touched.
 */
export default function CommuteMode({ spaceId, onWindowsChanged, onSessionExpired }: CommuteModeProps) {
  const [schedules, setSchedules] = useState<CommuteSchedule[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [days, setDays] = useState<number[]>([0, 1, 2, 3, 4]);
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('17:00');
  const [priceMode, setPriceMode] = useState<'free' | 'hourly'>('free');
  const [dollars, setDollars] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const timezone = useMemo(() => deviceTimezone(), []);

  useEffect(() => {
    let cancelled = false;
    api.commute
      .list(spaceId)
      .then((list) => {
        if (!cancelled) setSchedules(list);
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof SessionExpiredError) onSessionExpired();
        else setLoadError(e instanceof Error ? e.message : 'Could not load commute schedules.');
      });
    return () => {
      cancelled = true;
    };
  }, [spaceId, onSessionExpired]);

  function toggleDay(d: number) {
    setDays((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d].sort()));
  }

  function validate(): string | null {
    if (days.length === 0) return 'Pick at least one weekday.';
    if (!startTime || !endTime) return 'Choose a start and end time.';
    const [sh, sm] = startTime.split(':').map(Number);
    const [eh, em] = endTime.split(':').map(Number);
    const minutes = eh * 60 + em - (sh * 60 + sm);
    if (Number.isNaN(minutes) || minutes <= 0) return 'The end time must be after the start time.';
    if (minutes < MIN_MINUTES) return 'Commute windows need to be at least 30 minutes long.';
    if (priceMode === 'hourly') {
      const amount = Number(dollars);
      if (dollars.trim() === '' || Number.isNaN(amount) || amount <= 0)
        return 'Enter an hourly price above $0, or choose Free.';
      if (amount > MAX_DOLLARS) return 'The hourly price can\u2019t be more than $100.';
    }
    return null;
  }

  const preview = useMemo(() => {
    if (days.length === 0 || !startTime || !endTime) return [];
    return [...days]
      .sort()
      .slice(0, 3)
      .map((d) => ({
        day: d,
        date: nextOccurrence(d),
      }));
  }, [days, startTime, endTime]);

  async function create() {
    if (saving) return;
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const rateCents = priceMode === 'free' ? null : Math.round(Number(dollars) * 100);
      // One entry per weekday; the server rejects duplicates with a friendly message.
      for (const dayOfWeek of days) {
        const created = await api.commute.create(spaceId, {
          dayOfWeek,
          startTime,
          endTime,
          hourlyRateCents: rateCents,
          timezone,
        });
        setSchedules((prev) =>
          prev === null
            ? [created]
            : [...prev.filter((s) => s.id !== created.id), created].sort(
                (a, b) => a.dayOfWeek - b.dayOfWeek,
              ),
        );
      }
      setShowForm(false);
      onWindowsChanged();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      else if (e instanceof ApiError) setError(e.message);
      else setError(e instanceof Error ? e.message : 'Could not save the commute schedule.');
    } finally {
      setSaving(false);
    }
  }

  async function togglePause(schedule: CommuteSchedule) {
    if (busyId) return;
    setBusyId(schedule.id);
    setError(null);
    try {
      const updated = schedule.active
        ? await api.commute.pause(schedule.id)
        : await api.commute.resume(schedule.id);
      setSchedules((prev) => prev?.map((s) => (s.id === updated.id ? updated : s)) ?? null);
      onWindowsChanged();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      else if (e instanceof ApiError) setError(e.message);
      else setError(e instanceof Error ? e.message : 'Could not update the schedule.');
    } finally {
      setBusyId(null);
    }
  }

  async function remove(scheduleId: string) {
    if (busyId) return;
    if (confirmDeleteId !== scheduleId) {
      setConfirmDeleteId(scheduleId);
      return;
    }
    setBusyId(scheduleId);
    setConfirmDeleteId(null);
    try {
      await api.commute.remove(scheduleId);
      setSchedules((prev) => prev?.filter((s) => s.id !== scheduleId) ?? null);
      onWindowsChanged();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      else if (e instanceof ApiError) setError(e.message);
      else setError(e instanceof Error ? e.message : 'Could not delete the schedule.');
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="mt-5">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
        Commute mode
      </h2>
      <p className="mt-1 text-sm text-slate-600">
        Set a weekly pattern once — e.g. weekdays 9:00 AM – 5:00 PM — and your spot is
        shared automatically two weeks out. Pause any time; existing bookings are never
        cancelled.
      </p>

      {loadError && (
        <div role="alert" className="mt-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {loadError}
        </div>
      )}

      {schedules === null ? (
        <div className="mt-2 animate-pulse rounded-xl border border-slate-200 bg-white p-4" aria-label="Loading commute schedules">
          <div className="h-4 w-2/3 rounded bg-slate-200" />
        </div>
      ) : (
        <div className="mt-2 space-y-2">
          {schedules.map((s) => (
            <div key={s.id} className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-4">
              <div>
                <p className="text-sm font-semibold text-slate-900">
                  {WEEKDAY_FULL[s.dayOfWeek]} · {formatTimeOfDay(s.startTime)} – {formatTimeOfDay(s.endTime)}
                </p>
                <p className="mt-0.5 text-sm text-slate-600">
                  {formatRate(s.hourlyRateCents)}{' '}
                  <span
                    className={`ml-1 inline-block rounded-full px-2 py-0.5 text-xs font-semibold ${
                      s.active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-700'
                    }`}
                  >
                    {s.active ? 'Active' : 'Paused'}
                  </span>
                </p>
              </div>
              <div className="flex shrink-0 gap-2">
                <button
                  type="button"
                  disabled={busyId === s.id}
                  onClick={() => void togglePause(s)}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 disabled:opacity-50"
                >
                  {busyId === s.id ? '…' : s.active ? 'Pause' : 'Resume'}
                </button>
                <button
                  type="button"
                  disabled={busyId === s.id}
                  onClick={() => void remove(s.id)}
                  className={`rounded-lg px-3 py-2 text-sm font-semibold ${
                    confirmDeleteId === s.id
                      ? 'bg-red-600 text-white'
                      : 'border border-slate-300 text-slate-700'
                  } disabled:opacity-50`}
                >
                  {confirmDeleteId === s.id ? 'Tap again to delete' : 'Delete'}
                </button>
              </div>
            </div>
          ))}
          {schedules.length === 0 && !showForm && (
            <p className="rounded-xl border border-dashed border-slate-300 bg-white p-4 text-sm text-slate-600">
              No weekly pattern yet. Add one below and the next two weeks of shares appear automatically.
            </p>
          )}
        </div>
      )}

      {error && (
        <div role="alert" className="mt-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {!showForm ? (
        <button
          type="button"
          onClick={() => {
            setShowForm(true);
            setError(null);
          }}
          className="mt-3 w-full rounded-xl border-2 border-dashed border-sky-300 bg-sky-50 px-4 py-3 text-sm font-bold text-sky-800"
        >
          + Add a weekly pattern
        </button>
      ) : (
        <div className="mt-3 rounded-xl border border-slate-200 bg-white p-4">
          <div className="flex flex-wrap gap-2" role="group" aria-label="Weekdays">
            {WEEKDAYS.map((label, d) => (
              <button
                key={label}
                type="button"
                aria-pressed={days.includes(d)}
                onClick={() => toggleDay(d)}
                className={`rounded-lg px-3 py-2 text-sm font-semibold ${
                  days.includes(d)
                    ? 'bg-sky-700 text-white'
                    : 'border border-slate-300 bg-white text-slate-700'
                }`}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="mt-3 grid grid-cols-2 gap-3">
            <label className="block">
              <span className="text-sm font-semibold text-slate-700">Gone from</span>
              <input
                type="time"
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </label>
            <label className="block">
              <span className="text-sm font-semibold text-slate-700">Back by</span>
              <input
                type="time"
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </label>
          </div>

          <div className="mt-3 flex gap-2" role="group" aria-label="Price">
            {(['free', 'hourly'] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                aria-pressed={priceMode === mode}
                onClick={() => setPriceMode(mode)}
                className={`rounded-lg px-3 py-2 text-sm font-semibold ${
                  priceMode === mode
                    ? 'bg-sky-700 text-white'
                    : 'border border-slate-300 bg-white text-slate-700'
                }`}
              >
                {mode === 'free' ? 'Free' : 'Hourly price'}
              </button>
            ))}
            {priceMode === 'hourly' && (
              <input
                type="number"
                min="0.01"
                max="100"
                step="0.01"
                inputMode="decimal"
                placeholder="$ / hour"
                value={dollars}
                onChange={(e) => setDollars(e.target.value)}
                aria-label="Hourly price in dollars"
                className="w-28 rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            )}
          </div>

          <p className="mt-3 text-xs text-slate-500">
            Using your device&apos;s timezone ({timezone}) — shares land at the right local hour.
          </p>

          {preview.length > 0 && (
            <div className="mt-2 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-700">
              <span className="font-semibold">Next shares:</span>{' '}
              {preview
                .map((p) => `${formatShortDate(p.date)} · ${formatTimeOfDay(startTime)} – ${formatTimeOfDay(endTime)}`)
                .join(' · ')}
            </div>
          )}

          <div className="mt-3 flex gap-2">
            <button
              type="button"
              disabled={saving}
              onClick={() => void create()}
              className="flex-1 rounded-xl bg-sky-700 px-4 py-3 text-sm font-bold text-white disabled:opacity-50"
            >
              {saving ? 'Saving…' : `Save${days.length > 1 ? ` ${days.length} days` : ''}`}
            </button>
            <button
              type="button"
              onClick={() => {
                setShowForm(false);
                setError(null);
              }}
              className="rounded-xl border border-slate-300 px-4 py-3 text-sm font-semibold text-slate-700"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
