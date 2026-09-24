import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { api } from '../api/client';
import { ApiError, ReservationDetail as Detail } from '../api/types';
import { ActiveParkingBanner } from '../components/ActiveParkingBanner';
import { formatCents } from '../utils/money';
import { formatDateTime } from '../utils/time';

const STATUS_STYLES: Record<Detail['status'], string> = {
  CONFIRMED: 'bg-emerald-100 text-emerald-800',
  CANCELLED: 'bg-slate-200 text-slate-600',
  COMPLETED: 'bg-slate-200 text-slate-600',
};

/**
 * Reservation confirmation + detail. This is the only screen in the app
 * that shows the exact address, space label, and parking instructions —
 * booking is the act that reveals them. Cancelling an upcoming reservation
 * is offered here with a confirmation step.
 */
export default function ReservationDetail() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      setDetail(await api.reservations.get(id));
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 403
          ? "This reservation isn't yours."
          : err instanceof Error
            ? err.message
            : 'Could not load the reservation.',
      );
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const cancel = async () => {
    if (!id || cancelling) return;
    setCancelling(true);
    try {
      const cancelled = await api.reservations.cancel(id);
      // The cancel endpoint returns the summary; keep the already-loaded
      // detail fields (address, instructions) and flip the status.
      setDetail((prev) => (prev ? { ...prev, status: cancelled.status } : prev));
      setConfirmingCancel(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not cancel. Please try again.');
    } finally {
      setCancelling(false);
    }
  };

  if (loading) {
    return <p className="py-8 text-center text-sm text-slate-500">Loading reservation…</p>;
  }

  if (error || !detail) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">Couldn&apos;t open that reservation</p>
        <p className="text-sm text-slate-500">{error ?? 'Please try again.'}</p>
        <Link
          to="/reservations"
          className="inline-block rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white"
        >
          My reservations
        </Link>
      </div>
    );
  }

  const upcoming = detail.status === 'CONFIRMED' && new Date(detail.arrival).getTime() > Date.now();
  // Parked right now: CONFIRMED and arrival <= now < departure.
  const active =
    detail.status === 'CONFIRMED' &&
    new Date(detail.arrival).getTime() <= Date.now() &&
    new Date(detail.departure).getTime() > Date.now();

  return (
    <div className="space-y-4">
      <Link to="/reservations" className="text-sm font-medium text-sky-700">
        ← My reservations
      </Link>

      {active && <ActiveParkingBanner detail={detail} />}

      <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-center">
        <p className="text-sm font-medium text-emerald-800">
          {detail.status === 'CONFIRMED' ? '🎉 Reservation confirmed' : `Reservation ${detail.status.toLowerCase()}`}
        </p>
        <p className="mt-1 text-2xl font-bold tracking-widest text-emerald-900">{detail.code}</p>
        <p className="mt-1 text-xs text-emerald-700">
          Quote this code if you need to reach the host
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <div className="flex items-center justify-between">
          <h1 className="text-lg font-bold text-slate-900">Where to park</h1>
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STATUS_STYLES[detail.status]}`}
          >
            {detail.status}
          </span>
        </div>
        <address className="mt-2 text-sm not-italic leading-relaxed text-slate-800">
          <span className="font-semibold">{detail.address}</span>
          <br />
          {detail.city}, {detail.state} {detail.zipCode}
        </address>
        <p className="mt-2 text-sm text-slate-600">
          <span className="font-semibold">Spot:</span> {detail.spaceLabel} · {detail.spaceTypeLabel}
        </p>
        <p className="mt-1 text-sm text-slate-600">
          <span className="font-semibold">Host:</span> {detail.hostName}
        </p>
        {detail.parkingInstructions && (
          <p className="mt-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
            <span className="font-semibold">Host&apos;s instructions:</span> {detail.parkingInstructions}
          </p>
        )}
      </div>

      <dl className="space-y-2 rounded-xl border border-slate-200 bg-white p-4 text-sm">
        <div className="flex justify-between">
          <dt className="text-slate-500">Arriving</dt>
          <dd className="font-medium text-slate-800">{formatDateTime(detail.arrival)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Leaving</dt>
          <dd className="font-medium text-slate-800">{formatDateTime(detail.departure)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Total</dt>
          <dd className="font-medium text-slate-800">
            {detail.hourlyRateCents == null ? 'Free' : formatCents(detail.totalCents)}
          </dd>
        </div>
        {detail.cancelledAt && (
          <div className="flex justify-between">
            <dt className="text-slate-500">Cancelled</dt>
            <dd className="font-medium text-slate-800">{formatDateTime(detail.cancelledAt)}</dd>
          </div>
        )}
      </dl>

      {upcoming && !confirmingCancel && (
        <button
          type="button"
          onClick={() => setConfirmingCancel(true)}
          className="w-full rounded-lg border border-red-300 bg-white py-3 font-semibold text-red-700"
        >
          Cancel reservation
        </button>
      )}

      {upcoming && confirmingCancel && (
        <div className="rounded-xl border border-red-200 bg-red-50 p-4" role="alertdialog">
          <p className="text-sm font-semibold text-red-900">Cancel this reservation?</p>
          <p className="mt-1 text-sm text-red-700">
            The spot will be released for other drivers immediately.
          </p>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setConfirmingCancel(false)}
              disabled={cancelling}
              className="rounded-lg border border-slate-300 bg-white py-2.5 font-semibold text-slate-700 disabled:opacity-60"
            >
              Keep it
            </button>
            <button
              type="button"
              onClick={cancel}
              disabled={cancelling}
              className="rounded-lg bg-red-600 py-2.5 font-semibold text-white disabled:opacity-60"
            >
              {cancelling ? 'Cancelling…' : 'Yes, cancel'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
