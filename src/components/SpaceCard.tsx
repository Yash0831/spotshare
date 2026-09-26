import { Link } from 'react-router-dom';

import { api } from '../api/client';
import {
  PARKING_TYPE_LABELS,
  PublicSpace,
  VEHICLE_SIZE_LABELS,
} from '../api/types';
import { formatCents, formatRate } from '../utils/money';
import { formatDateTime } from '../utils/time';

/**
 * One discovery result. Shows only what the privacy-safe DTO carries:
 * approximate location, price, distance, availability, type, vehicle fit,
 * photos, and the host's first name + last initial. There is deliberately
 * no exact address here — it is revealed only after a reservation.
 *
 * `arrival`/`departure` are the searched trip times (ISO instants) carried
 * into the detail → Reserve flow so the booking stays consistent with the
 * search that surfaced the space.
 */
export default function SpaceCard({
  space,
  arrival,
  departure,
}: {
  space: PublicSpace;
  arrival: string;
  departure: string;
}) {
  const photo = space.photos[0];
  return (
    <Link
      to={`/spaces/${space.id}`}
      state={{ space, arrival, departure }}
      className="block overflow-hidden rounded-2xl border border-white bg-white/90 shadow-lg shadow-violet-100 backdrop-blur transition hover:shadow-xl hover:shadow-violet-200"
    >
      {photo ? (
        <img
          src={api.photoUrl(photo)}
          alt={`${PARKING_TYPE_LABELS[space.parkingType]} parking space`}
          className="h-36 w-full object-cover"
          loading="lazy"
            decoding="async"
        />
      ) : (
        <div className="flex h-36 w-full items-center justify-center bg-gradient-to-br from-violet-200 via-fuchsia-100 to-amber-100 text-3xl" aria-hidden>
          🅿️
        </div>
      )}
      <div className="p-4">
        <div className="flex items-center justify-between gap-2">
          <p className="rounded-full bg-gradient-to-r from-violet-600 to-fuchsia-600 px-3 py-1 text-lg font-bold text-white shadow-md shadow-fuchsia-600/25">{formatRate(space.hourlyRateCents)}</p>
          <p className="shrink-0 rounded-full bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-800">
            {space.distanceMiles.toFixed(1)} mi away
          </p>
        </div>
        <p className="mt-2 text-sm text-slate-600">
          {space.areaLabel}, {space.city}, {space.state}
        </p>
        <div className="mt-2 flex flex-wrap gap-1.5">
          <span className="rounded-full bg-violet-100 px-2.5 py-0.5 text-xs font-semibold text-violet-800">
            {PARKING_TYPE_LABELS[space.parkingType]}
          </span>
          {space.vehicleSizes.map((v) => (
            <span key={v} className="rounded-full bg-sky-100 px-2.5 py-0.5 text-xs font-semibold text-sky-800">
              {VEHICLE_SIZE_LABELS[v]}
            </span>
          ))}
          {space.covered && (
            <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-xs font-semibold text-emerald-800">
              Covered
            </span>
          )}
          {space.evCharging && (
            <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-800">
              ⚡ EV charging
            </span>
          )}
        </div>
        <p className="mt-1 text-sm text-slate-500">
          Available {formatDateTime(space.windowStartsAt)} – {formatDateTime(space.windowEndsAt)}
        </p>
        <div className="mt-2 flex items-center justify-between">
          <p className="text-sm text-slate-500">Hosted by {space.hostName}</p>
          <p className="text-sm font-semibold text-slate-700">
            Est. total {formatCents(space.estimatedTotalCents)}
          </p>
        </div>
      </div>
    </Link>
  );
}
