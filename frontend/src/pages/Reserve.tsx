import { useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';

import { api } from '../api/client';
import { ApiError, PublicSpace } from '../api/types';
import { formatCents, formatRate } from '../utils/money';
import { toLocalInputValue } from '../utils/dateInput';
import { newIdempotencyKey } from '../utils/idempotency';
import { formatDateTime } from '../utils/time';

interface ReserveState {
  space?: PublicSpace;
  arrival?: string;
  departure?: string;
}

/** Display-only estimate. The server computes the binding prorated total. */
function estimateTotalCents(
  hourlyRateCents: number | null,
  arrivalIso: string,
  departureIso: string,
): number | null {
  if (hourlyRateCents == null) return null;
  const minutes = (new Date(departureIso).getTime() - new Date(arrivalIso).getTime()) / 60000;
  if (!Number.isFinite(minutes) || minutes <= 0) return 0;
  // Math.round rounds half up for positive values, matching the backend.
  return Math.round((hourlyRateCents * minutes) / 60);
}

/** Clamp an ISO instant into [min, max]; fall back to min when invalid. */
function clampIso(iso: string | undefined, minMs: number, maxMs: number): string {
  const t = iso ? new Date(iso).getTime() : NaN;
  const clamped = Number.isFinite(t) ? Math.min(Math.max(t, minMs), maxMs) : minMs;
  return new Date(clamped).toISOString();
}

function friendlyError(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'SPACE_JUST_RESERVED':
        return 'Someone just booked this spot for those times. Please pick another time or another spot.';
      case 'PERIOD_NOT_AVAILABLE':
        return "Those times are no longer inside the host's share window — the host may have changed it.";
      case 'CANNOT_BOOK_OWN_SPACE':
        return "This is your own spot — you can't reserve it.";
      case 'ARRIVAL_IN_PAST':
        return 'Your arrival time is in the past. Please pick a future time.';
      case 'INVALID_PERIOD':
        return 'Your departure has to be after your arrival.';
      case 'SPACE_NOT_FOUND':
        return "This spot isn't available anymore.";
      default:
        return err.message;
    }
  }
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.';
}

/**
 * Reserve: the booking screen for one discovery result. Arrival/departure
 * pickers are constrained to the host's share window, the price estimate is
 * prorated live, and the submit is guarded against double-taps.
 *
 * Idempotency: one key is generated when this screen mounts and reused for
 * every retry of the attempt — if the request is sent twice (double-tap,
 * dropped connection), the server answers 200 with the original reservation
 * instead of creating a second one.
 */
export default function Reserve() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as ReserveState | null;
  const space = state?.space;

  // One idempotency key per booking attempt — stable across retries.
  const [idempotencyKey] = useState(() => newIdempotencyKey());

  const bounds = useMemo(() => {
    if (!space) return null;
    const windowStart = new Date(space.windowStartsAt).getTime();
    const windowEnd = new Date(space.windowEndsAt).getTime();
    // The window may have started already: earliest bookable is now.
    const min = Math.max(windowStart, Date.now());
    return { min, max: windowEnd, minInput: toLocalInputValue(new Date(min)), maxInput: toLocalInputValue(new Date(windowEnd)) };
  }, [space]);

  const [arrivalInput, setArrivalInput] = useState(() =>
    bounds ? toLocalInputValue(new Date(clampIso(state?.arrival, bounds.min, bounds.max))) : '',
  );
  const [departureInput, setDepartureInput] = useState(() =>
    bounds ? toLocalInputValue(new Date(clampIso(state?.departure, bounds.min, bounds.max))) : '',
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!space || space.id !== id || !bounds) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">This booking needs a fresh search</p>
        <p className="text-sm text-slate-500">
          Please pick the spot again from Explore or Park Now so we can book the right times.
        </p>
        <Link to="/explore" className="inline-block rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white">
          Back to Explore
        </Link>
      </div>
    );
  }

  const arrivalIso = arrivalInput ? new Date(arrivalInput).toISOString() : '';
  const departureIso = departureInput ? new Date(departureInput).toISOString() : '';
  const estimate = estimateTotalCents(space.hourlyRateCents, arrivalIso, departureIso);

  const validation: string | null =
    !arrivalInput || !departureInput
      ? 'Pick an arrival and a departure time.'
      : new Date(departureInput) <= new Date(arrivalInput)
        ? 'Your departure has to be after your arrival.'
        : new Date(arrivalInput).getTime() < bounds.min - 60_000
          ? "Arrival can't be before the host's share window."
          : new Date(departureInput).getTime() > bounds.max
            ? "Departure can't be after the host's share window ends."
            : null;

  const submit = async () => {
    if (validation || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const reservation = await api.reservations.create(
        { spaceId: space.id, arrival: arrivalIso, departure: departureIso },
        idempotencyKey,
      );
      navigate(`/reservations/${reservation.id}`, { replace: true });
    } catch (err) {
      setError(friendlyError(err));
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4">
      <Link to={`/spaces/${space.id}`} state={{ space }} className="text-sm font-medium text-sky-700">
        ← Back to spot
      </Link>
      <h1 className="text-xl font-bold text-slate-900">Reserve this spot</h1>

      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <p className="font-semibold text-slate-900">{formatRate(space.hourlyRateCents)}</p>
        <p className="mt-1 text-sm text-slate-600">
          {space.areaLabel}, {space.city}, {space.state} · Hosted by {space.hostName}
        </p>
        <p className="mt-1 text-sm text-slate-500">
          Shared {formatDateTime(space.windowStartsAt)} – {formatDateTime(space.windowEndsAt)}
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="arrival" className="text-sm font-medium text-slate-700">
            Arriving
          </label>
          <input
            id="arrival"
            type="datetime-local"
            value={arrivalInput}
            min={bounds.minInput}
            max={bounds.maxInput}
            onChange={(e) => setArrivalInput(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
          />
        </div>
        <div>
          <label htmlFor="departure" className="text-sm font-medium text-slate-700">
            Leaving
          </label>
          <input
            id="departure"
            type="datetime-local"
            value={departureInput}
            min={bounds.minInput}
            max={bounds.maxInput}
            onChange={(e) => setDepartureInput(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
          />
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <div className="flex items-baseline justify-between">
          <p className="text-sm text-slate-600">Estimated total</p>
          <p className="text-lg font-bold text-slate-900">
            {estimate == null ? 'Free' : formatCents(estimate)}
          </p>
        </div>
        <p className="mt-1 text-xs text-slate-500">
          {space.hourlyRateCents == null
            ? 'This is a free share — no payment needed.'
            : `Prorated at ${formatRate(space.hourlyRateCents)}. Final total confirmed at booking.`}
        </p>
      </div>

      {validation && (
        <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {validation}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <button
        type="button"
        onClick={submit}
        disabled={submitting || !!validation}
        className="w-full rounded-lg bg-sky-600 py-3 font-semibold text-white disabled:opacity-60"
      >
        {submitting ? 'Reserving…' : 'Confirm reservation'}
      </button>
      <p className="text-center text-xs text-slate-500">
        Beta — no payment is collected. Your reservation holds the spot, and the exact address
        is revealed right after.
      </p>
    </div>
  );
}
