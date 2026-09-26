import { formatCents } from '../utils/money';
import { formatDateTime } from '../utils/time';

const OPTIONS = [30, 60, 120];

function optionLabel(minutes: number): string {
  return minutes >= 60 ? `+${minutes / 60} hr` : `+${minutes} min`;
}

interface ExtendStayPanelProps {
  arrival: string;
  departure: string;
  hourlyRateCents: number | null;
  extendMinutes: number;
  onSelectMinutes: (minutes: number) => void;
  onConfirm: () => void;
  onClose: () => void;
  saving: boolean;
  error: string | null;
}

/**
 * Extend-stay picker: how much longer, the new departure time, and the
 * repriced total at the reservation's snapshot hourly rate. The server
 * rechecks everything atomically, so confirming here is the snipe-safe
 * path — no re-search, no re-book.
 */
export function ExtendStayPanel({
  arrival,
  departure,
  hourlyRateCents,
  extendMinutes,
  onSelectMinutes,
  onConfirm,
  onClose,
  saving,
  error,
}: ExtendStayPanelProps) {
  const newDeparture = new Date(new Date(departure).getTime() + extendMinutes * 60_000);
  // Mirror the server's prorating (whole minutes × rate / 60) for the estimate.
  const totalMinutes = Math.floor((newDeparture.getTime() - new Date(arrival).getTime()) / 60_000);
  const newTotalCents =
    hourlyRateCents == null ? null : Math.round((hourlyRateCents * totalMinutes) / 60);

  return (
    <div className="rounded-xl border border-violet-200 bg-violet-50 p-4">
      <p className="text-sm font-semibold text-violet-900">Extend your stay</p>
      <p className="mt-1 text-sm text-violet-700">
        Currently leaving {formatDateTime(departure)}
      </p>

      <div className="mt-3 grid grid-cols-3 gap-2" role="group" aria-label="How much longer">
        {OPTIONS.map((minutes) => {
          const selected = minutes === extendMinutes;
          return (
            <button
              key={minutes}
              type="button"
              onClick={() => onSelectMinutes(minutes)}
              aria-pressed={selected}
              className={`rounded-lg border py-2.5 text-sm font-semibold ${
                selected
                  ? 'border-violet-600 bg-violet-600 text-white'
                  : 'border-violet-200 bg-white text-violet-700'
              }`}
            >
              {optionLabel(minutes)}
            </button>
          );
        })}
      </div>

      <p className="mt-3 text-sm text-violet-900">
        New departure: <span className="font-semibold">{formatDateTime(newDeparture.toISOString())}</span>
        <br />
        New total: <span className="font-semibold">{formatCents(newTotalCents)}</span>
      </p>

      {error && (
        <p role="alert" className="mt-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2">
        <button
          type="button"
          onClick={onClose}
          disabled={saving}
          className="rounded-lg border border-slate-300 bg-white py-2.5 font-semibold text-slate-700 disabled:opacity-60"
        >
          Not now
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={saving}
          className="rounded-lg btn-brand py-2.5 font-semibold text-white disabled:opacity-60"
        >
          {saving ? 'Extending…' : 'Confirm extension'}
        </button>
      </div>
    </div>
  );
}
