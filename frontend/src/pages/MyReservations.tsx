import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import { Reservation } from '../api/types';
import { ReservationSkeleton } from '../components/Skeleton';
import { useCountdown } from '../hooks/useCountdown';
import { formatCents } from '../utils/money';
import { formatDateTime } from '../utils/time';

const STATUS_STYLES: Record<Reservation['status'], string> = {
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  CANCELLED: 'bg-slate-200 text-slate-600',
  COMPLETED: 'bg-slate-200 text-slate-600',
};

function statusLabel(reservation: Reservation): string {
  if (reservation.status !== 'CANCELLED') return reservation.status;
  return reservation.cancelledBy === 'HOST' ? 'Cancelled by host' : 'Cancelled';
}

/** Live "1h 42m left" chip for an active reservation. */
function MiniCountdown({ departure }: { departure: string }) {
  const countdown = useCountdown(departure);
  return (
    <span className="font-semibold tabular-nums text-emerald-700">
      {countdown.isOver ? "Time's up" : `${countdown.label} left`}
    </span>
  );
}

function ReservationRow({
  reservation,
  onCancel,
  cancelling,
}: {
  reservation: Reservation;
  /** Present only for upcoming reservations: renders the cancel action. */
  onCancel?: () => void;
  cancelling?: boolean;
}) {
  const active =
    reservation.status === 'CONFIRMED' &&
    new Date(reservation.arrival).getTime() <= Date.now() &&
    new Date(reservation.departure).getTime() > Date.now();

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <Link to={`/reservations/${reservation.id}`} className="block">
        <div className="flex items-center justify-between gap-2">
          <p className="font-mono text-sm font-bold tracking-wider text-slate-900">
            {reservation.code}
          </p>
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STATUS_STYLES[reservation.status]}`}
          >
            {statusLabel(reservation)}
          </span>
        </div>
        <p className="mt-1 text-sm text-slate-700">
          {formatDateTime(reservation.arrival)} – {formatDateTime(reservation.departure)}
        </p>
        <div className="mt-1 flex items-center justify-between text-sm">
          <p className="text-slate-500">
            {reservation.spaceTypeLabel} · {reservation.hostName}
          </p>
          <p className="font-semibold text-slate-800">
            {reservation.hourlyRateCents == null ? 'Free' : formatCents(reservation.totalCents)}
          </p>
        </div>
      </Link>
      {active && (
        <div className="mt-2 border-t border-slate-100 pt-2 text-sm" aria-live="polite">
          <MiniCountdown departure={reservation.departure} />
        </div>
      )}
      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          disabled={cancelling}
          className="mt-2 w-full rounded-lg border border-red-300 py-2 text-sm font-semibold text-red-700 disabled:opacity-60"
        >
          {cancelling ? 'Cancelling…' : 'Cancel reservation'}
        </button>
      )}
    </div>
  );
}

/**
 * My Reservations: the driver's bookings in three sections — Active (with
 * a live mini countdown), Upcoming, Past. Cancel an upcoming reservation
 * with a confirm step. Summaries never carry the exact address — tap
 * through to the detail for the reveal. Loading, empty, and error states
 * included.
 */
export default function MyReservations() {
  const [active, setActive] = useState<Reservation[] | null>(null);
  const [upcoming, setUpcoming] = useState<Reservation[] | null>(null);
  const [past, setPast] = useState<Reservation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [activeList, upcomingList, pastList] = await Promise.all([
        api.reservations.mine('active'),
        api.reservations.mine('upcoming'),
        api.reservations.mine('past'),
      ]);
      setActive(activeList);
      setUpcoming(upcomingList);
      setPast(pastList);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load reservations.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const cancel = async (id: string) => {
    if (cancelling) return;
    setCancelling(true);
    try {
      await api.reservations.cancel(id);
      setConfirmingId(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not cancel. Please try again.');
    } finally {
      setCancelling(false);
    }
  };

  const loading = active === null || upcoming === null || past === null;

  if (error) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">Couldn&apos;t load reservations</p>
        <p role="alert" className="text-sm text-slate-500">
          {error}
        </p>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white"
        >
          Try again
        </button>
      </div>
    );
  }

  if (loading) {
    return (
      <>
        <h1 className="text-xl font-bold text-slate-900">My reservations</h1>
        <ReservationSkeleton />
      </>
    );
  }

  const total = (active ?? []).length + (upcoming ?? []).length + (past ?? []).length;

  if (total === 0) {
    return (
      <div className="space-y-4 text-center">
        <h1 className="text-xl font-bold text-slate-900">My reservations</h1>
        <div className="rounded-xl border border-slate-200 bg-white p-8">
          <p className="text-3xl" aria-hidden>
            🅿️
          </p>
          <p className="mt-2 font-semibold text-slate-800">No reservations yet</p>
          <p className="mt-1 text-sm text-slate-500">
            Find a shared spot and reserve it — the exact address is revealed on booking.
          </p>
          <Link
            to="/explore"
            className="mt-4 inline-block rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white"
          >
            Find parking
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <h1 className="text-xl font-bold text-slate-900">My reservations</h1>

      {(active ?? []).length > 0 && (
        <section aria-label="Active reservations">
          <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Active ({active!.length})
          </h2>
          <div className="space-y-3">
            {active!.map((r) => (
              <ReservationRow key={r.id} reservation={r} />
            ))}
          </div>
        </section>
      )}

      <section aria-label="Upcoming reservations">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Upcoming ({upcoming!.length})
        </h2>
        {(upcoming ?? []).length === 0 ? (
          <p className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-500">
            Nothing upcoming.
          </p>
        ) : (
          <div className="space-y-3">
            {upcoming!.map((r) => (
              <ReservationRow
                key={r.id}
                reservation={r}
                cancelling={cancelling && confirmingId === r.id}
                onCancel={confirmingId === r.id ? undefined : () => setConfirmingId(r.id)}
              />
            ))}
          </div>
        )}
        {confirmingId && (
          <div className="mt-3 rounded-xl border border-red-200 bg-red-50 p-4" role="alertdialog">
            <p className="text-sm font-semibold text-red-900">Cancel this reservation?</p>
            <p className="mt-1 text-sm text-red-700">
              The spot will be released for other drivers immediately.
            </p>
            <div className="mt-3 grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setConfirmingId(null)}
                disabled={cancelling}
                className="rounded-lg border border-slate-300 bg-white py-2.5 font-semibold text-slate-700 disabled:opacity-60"
              >
                Keep it
              </button>
              <button
                type="button"
                onClick={() => void cancel(confirmingId)}
                disabled={cancelling}
                className="rounded-lg bg-red-600 py-2.5 font-semibold text-white disabled:opacity-60"
              >
                {cancelling ? 'Cancelling…' : 'Yes, cancel'}
              </button>
            </div>
          </div>
        )}
      </section>

      {(past ?? []).length > 0 && (
        <section aria-label="Past reservations">
          <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Past ({past!.length})
          </h2>
          <div className="space-y-3">
            {past!.map((r) => (
              <ReservationRow key={r.id} reservation={r} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
