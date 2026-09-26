import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';

import { api } from '../api/client';
import { HostArrival, SessionExpiredError } from '../api/types';
import { formatDateTime } from '../utils/time';

/**
 * Today's arrivals: the host's bookings for the day across every space
 * they own — one tab instead of opening each space. Driver identity stays
 * privacy-safe (first name + last initial, spec §11); the host already
 * knows their own address, so none is shown. While a booking is live the
 * host also gets the driver's phone number to coordinate timing; it
 * vanishes once the booking ends. Cancelling an upcoming arrival takes
 * two taps and is recorded as host-cancelled, never silent.
 */
export default function Arrivals() {
  const [arrivals, setArrivals] = useState<HostArrival[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setArrivals(await api.reservations.hostArrivalsAll());
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not load arrivals. Please try again.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function cancelArrival(id: string) {
    if (cancellingId !== id) {
      setCancellingId(id);
      return;
    }
    setCancellingId(null);
    try {
      await api.reservations.cancel(id);
      await load();
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not cancel. Please try again.');
    }
  }

  if (expired) return <Navigate to="/login" replace />;

  return (
    <div>
      <h1 className="text-xl font-bold bg-gradient-to-r from-emerald-500 to-teal-600 bg-clip-text text-transparent">Today&apos;s arrivals</h1>
      <p className="mt-1 text-sm text-slate-600">
        Bookings for all your spaces, in one place.
      </p>

      {error && (
        <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {arrivals === null ? (
        <div className="mt-4 animate-pulse rounded-xl border border-slate-200 bg-white p-4" aria-label="Loading arrivals">
          <div className="h-3 w-1/2 rounded bg-slate-200" />
        </div>
      ) : arrivals.length === 0 ? (
        <p className="mt-4 rounded-xl border border-dashed border-slate-300 bg-white p-4 text-sm text-slate-600">
          No arrivals today. When drivers book any of your spaces, they&apos;ll show up here.
        </p>
      ) : (
        <div className="mt-4 space-y-2">
          {arrivals.map((a) => {
            const cancellable =
              a.status === 'CONFIRMED' && new Date(a.arrival).getTime() > Date.now();
            return (
              <div key={a.id} className="rounded-xl border border-slate-200 bg-white p-4">
                <div className="flex items-center justify-between gap-2">
                  <Link
                    to={`/parking/${a.spaceId}`}
                    className="font-semibold text-violet-700 hover:underline"
                  >
                    {a.spaceLabel}
                  </Link>
                  <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-600">
                    {a.status === 'CANCELLED'
                      ? a.cancelledBy === 'HOST'
                        ? 'Cancelled by you'
                        : 'Cancelled'
                      : a.status}
                  </span>
                </div>
                <p className="mt-1 font-semibold text-slate-900">{a.driverName}</p>
                {a.driverPhone && (
                  <p className="mt-1 text-sm text-slate-600">
                    <a
                      href={`tel:${a.driverPhone}`}
                      className="font-medium text-violet-700 underline"
                    >
                      📞 {a.driverPhone}
                    </a>
                  </p>
                )}
                <p className="mt-1 text-sm text-slate-600">
                  {formatDateTime(a.arrival)} – {formatDateTime(a.departure)} ·{' '}
                  <span className="font-mono">{a.code}</span>
                </p>
                {cancellable && (
                  <button
                    type="button"
                    onClick={() => void cancelArrival(a.id)}
                    className={`mt-2 inline-flex min-h-[44px] items-center rounded-lg px-3 py-2 text-sm font-semibold ${
                      cancellingId === a.id
                        ? 'bg-red-600 text-white'
                        : 'border border-slate-300 text-slate-700'
                    }`}
                  >
                    {cancellingId === a.id ? 'Tap again to cancel' : 'Cancel reservation'}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
