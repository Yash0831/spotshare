import { useCountdown } from '../hooks/useCountdown';
import { ReservationDetail } from '../api/types';
import { formatTime } from '../utils/time';

/**
 * The active-parking screen section (spec §2/§6): a prominent "YOU'RE
 * PARKED" card with a live countdown ("1 hr 42 min remaining, leave by
 * 10:30 PM"), a persistent in-app banner during the last 15 minutes, and a
 * Get directions link. In-app only — V1 has no push notifications or SMS,
 * so this countdown and its banner are the reminder.
 */
export function ActiveParkingBanner({ detail }: { detail: ReservationDetail }) {
  const countdown = useCountdown(detail.departure);
  const directionsUrl = `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(
    `${detail.address}, ${detail.city}, ${detail.state} ${detail.zipCode}`,
  )}`;

  return (
    <section aria-label="Active parking countdown" className="space-y-3">
      <div className="rounded-2xl bg-emerald-700 p-5 text-center text-white shadow">
        <p className="text-sm font-bold uppercase tracking-widest text-emerald-100">You&apos;re parked</p>
        <p className="mt-2 text-3xl font-extrabold tabular-nums">
          {countdown.isOver ? "Time's up" : `${countdown.label} remaining`}
        </p>
        <p className="mt-1 text-sm text-emerald-100">Leave by {formatTime(detail.departure)}</p>
      </div>

      {countdown.isFinalStretch && (
        <div
          role="alert"
          className="rounded-xl border-2 border-amber-500 bg-amber-50 p-4 text-center"
        >
          <p className="font-bold text-amber-900">
            15 minutes left — please head back to your car.
          </p>
        </div>
      )}

      {countdown.isOver && (
        <div role="alert" className="rounded-xl border-2 border-red-400 bg-red-50 p-4 text-center">
          <p className="font-bold text-red-900">Your time is up — please move your car now.</p>
        </div>
      )}

      <a
        href={directionsUrl}
        target="_blank"
        rel="noopener noreferrer"
        className="block rounded-xl bg-sky-600 px-4 py-3 text-center text-lg font-bold text-white"
      >
        Get directions
      </a>
    </section>
  );
}
