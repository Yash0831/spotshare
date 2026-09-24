import { useMemo, useState } from 'react';
import { api } from '../api/client';
import { ApiError, AvailabilityWindow, SessionExpiredError } from '../api/types';
import { formatDateTime, formatRemaining, formatTime } from '../utils/time';
import Dialog from './Dialog';

type Quick = 'now' | 15 | 30 | 60 | 'custom';
const QUICK_LABELS: { value: Quick; label: string }[] = [
  { value: 'now', label: 'Now' },
  { value: 15, label: '+15 min' },
  { value: 30, label: '+30 min' },
  { value: 60, label: '+1 hr' },
  { value: 'custom', label: 'Custom' },
];

interface ReturnEarlyDialogProps {
  window: AvailabilityWindow;
  spaceLabel: string;
  onClose: () => void;
  onUpdated: (w: AvailabilityWindow) => void;
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
 * "I'm back early": the host moves their return time sooner and the share
 * window shrinks. If a driver is parked past the requested return, the
 * server blocks it and tells us exactly when the host may return — the
 * dialog stays open so the host can pick a later time. Reservations are
 * never touched by this action.
 */
export default function ReturnEarlyDialog({
  window,
  spaceLabel,
  onClose,
  onUpdated,
  onSessionExpired,
}: ReturnEarlyDialogProps) {
  const [quick, setQuick] = useState<Quick>('now');
  const [customValue, setCustomValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const currentEnd = useMemo(() => new Date(window.endsAt).getTime(), [window.endsAt]);
  const minInput = useMemo(() => toLocalInputValue(new Date()), []);

  /** The effective new return time, or null when the custom input is invalid. */
  function newReturn(): Date | null {
    if (quick === 'custom') {
      const d = new Date(customValue);
      return Number.isNaN(d.getTime()) ? null : d;
    }
    if (quick === 'now') return new Date();
    return new Date(Date.now() + quick * 60 * 1000);
  }

  const nr = newReturn();
  const soonerMs = nr ? currentEnd - nr.getTime() : null;

  function validate(): string | null {
    const t = newReturn();
    if (!t) return 'Choose a valid return time.';
    if (t.getTime() <= Date.now() - 2 * 60 * 1000)
      return 'That time is in the past. Pick a time in the future.';
    if (t.getTime() >= currentEnd)
      return 'That\u2019s not earlier than your current return time.';
    return null;
  }

  async function confirm() {
    if (submitting) return;
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    const t = newReturn();
    if (!t) {
      setError('Choose a valid return time.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const updated = await api.availability.returnEarly(window.id, {
        newReturnTime: t.toISOString(),
      });
      onUpdated(updated);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        onSessionExpired();
        return;
      }
      if (e instanceof ApiError && e.code === 'RETURN_BLOCKED_BY_RESERVATION') {
        // A driver is parked: keep the dialog open so the host can pick a
        // later return. The server message already names the blocked time;
        // format the machine-readable earliest return in the viewer's zone.
        const earliest = e.details?.earliestReturnTime;
        setError(
          e.message +
            (typeof earliest === 'string'
              ? ` (Your time: ${formatTime(earliest)})`
              : ''),
        );
      } else {
        setError(
          e instanceof ApiError
            ? e.message
            : 'Could not update your return time. Please try again.',
        );
      }
    } finally {
      setSubmitting(false);
    }
  }

  const chipCls = (active: boolean) =>
    `flex-1 rounded-lg border px-2 py-3 text-sm font-semibold ${
      active
        ? 'border-sky-700 bg-sky-50 text-sky-800'
        : 'border-slate-300 bg-white text-slate-700'
    }`;

  return (
    <Dialog label="Return early" onClose={onClose}>
      <div className="mx-auto mb-4 h-1 w-10 rounded-full bg-slate-300" aria-hidden="true" />
      <h2 className="text-xl font-bold text-slate-900">I&rsquo;m back early</h2>
        <p className="mt-1 text-sm text-slate-600">
          {spaceLabel} is currently shared until{' '}
          <strong>{formatDateTime(window.endsAt)}</strong>. Pick your new return time — the
          share ends then and the listing disappears.
        </p>

        {error && (
          <div
            role="alert"
            className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {error}
          </div>
        )}

        <div className="mt-5">
          <p className="mb-2 text-sm font-semibold text-slate-700">New return time</p>
          <div className="flex gap-2" role="group" aria-label="New return time">
            {QUICK_LABELS.map(({ value, label }) => (
              <button
                key={String(value)}
                type="button"
                aria-pressed={quick === value}
                onClick={() => setQuick(value)}
                className={chipCls(quick === value)}
              >
                {label}
              </button>
            ))}
          </div>
          {quick === 'custom' && (
            <input
              type="datetime-local"
              aria-label="Custom new return time"
              min={minInput}
              max={toLocalInputValue(new Date(currentEnd - 60 * 1000))}
              value={customValue}
              onChange={(e) => setCustomValue(e.target.value)}
              className="mt-3 w-full rounded-lg border border-slate-300 bg-white px-4 py-3 text-base text-slate-900 focus:border-sky-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-600/40"
            />
          )}
        </div>

        {nr && soonerMs !== null && soonerMs > 0 && (
          <p className="mt-5 rounded-lg bg-slate-100 px-4 py-3 text-sm text-slate-700" aria-live="polite">
            Listing ends <strong>{formatTime(nr.toISOString())}</strong> —{' '}
            <strong>{formatRemaining(soonerMs)}</strong> sooner than planned.
          </p>
        )}

        <div className="mt-5 flex gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="rounded-lg border border-slate-300 px-5 py-3 text-base font-semibold text-slate-700 disabled:opacity-60"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void confirm()}
            disabled={submitting}
            className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-bold text-white shadow-sm disabled:opacity-60"
          >
            {submitting ? 'Updating…' : 'End share early'}
          </button>
        </div>
    </Dialog>
  );
}
