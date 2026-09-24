import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import { Reservation } from '../api/types';
import { formatCents } from '../utils/money';
import { formatDateTime } from '../utils/time';

const STATUS_STYLES: Record<Reservation['status'], string> = {
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  CANCELLED: 'bg-slate-200 text-slate-600',
  COMPLETED: 'bg-slate-200 text-slate-600',
};

function ReservationRow({ reservation }: { reservation: Reservation }) {
  return (
    <Link
      to={`/reservations/${reservation.id}`}
      className="block rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition-shadow hover:shadow"
    >
      <div className="flex items-center justify-between gap-2">
        <p className="font-mono text-sm font-bold tracking-wider text-slate-900">{reservation.code}</p>
        <span
          className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STATUS_STYLES[reservation.status]}`}
        >
          {reservation.status}
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
  );
}

/**
 * My Reservations: the driver's booking list, split into upcoming and past.
 * Summaries never carry the exact address — tap through to the detail for
 * the reveal. Loading, empty, and error states included.
 */
export default function MyReservations() {
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api.reservations
      .mine()
      .then((list) => {
        if (alive) setReservations(list);
      })
      .catch((err) => {
        if (alive) setError(err instanceof Error ? err.message : 'Could not load reservations.');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const { upcoming, past } = useMemo(() => {
    const now = Date.now();
    const up: Reservation[] = [];
    const pa: Reservation[] = [];
    for (const r of reservations) {
      // A reservation counts as upcoming while it's confirmed and hasn't
      // started yet; everything else (past, cancelled, completed) is history.
      if (r.status === 'CONFIRMED' && new Date(r.arrival).getTime() > now) up.push(r);
      else pa.push(r);
    }
    return { upcoming: up, past: pa };
  }, [reservations]);

  if (loading) {
    return <p className="py-8 text-center text-sm text-slate-500">Loading reservations…</p>;
  }

  if (error) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">Couldn&apos;t load reservations</p>
        <p role="alert" className="text-sm text-slate-500">
          {error}
        </p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white"
        >
          Try again
        </button>
      </div>
    );
  }

  if (reservations.length === 0) {
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

      <section aria-label="Upcoming reservations">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Upcoming ({upcoming.length})
        </h2>
        {upcoming.length === 0 ? (
          <p className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-500">
            Nothing upcoming — your past reservations are below.
          </p>
        ) : (
          <div className="space-y-3">
            {upcoming.map((r) => (
              <ReservationRow key={r.id} reservation={r} />
            ))}
          </div>
        )}
      </section>

      {past.length > 0 && (
        <section aria-label="Past reservations">
          <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Past ({past.length})
          </h2>
          <div className="space-y-3">
            {past.map((r) => (
              <ReservationRow key={r.id} reservation={r} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
