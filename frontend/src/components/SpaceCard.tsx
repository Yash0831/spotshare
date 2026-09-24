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
 */
export default function SpaceCard({ space }: { space: PublicSpace }) {
  const photo = space.photos[0];
  return (
    <Link
      to={`/spaces/${space.id}`}
      state={{ space }}
      className="block overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm transition-shadow hover:shadow"
    >
      {photo ? (
        <img
          src={api.photoUrl(photo)}
          alt={`${PARKING_TYPE_LABELS[space.parkingType]} parking space`}
          className="h-36 w-full object-cover"
          loading="lazy"
        />
      ) : (
        <div className="flex h-36 w-full items-center justify-center bg-slate-100 text-3xl" aria-hidden>
          🅿️
        </div>
      )}
      <div className="p-4">
        <div className="flex items-baseline justify-between gap-2">
          <p className="text-lg font-bold text-slate-900">{formatRate(space.hourlyRateCents)}</p>
          <p className="shrink-0 text-sm font-medium text-slate-500">
            {space.distanceMiles.toFixed(1)} mi away
          </p>
        </div>
        <p className="mt-1 text-sm text-slate-600">
          {space.areaLabel}, {space.city}, {space.state}
        </p>
        <p className="mt-2 text-sm text-slate-700">
          {PARKING_TYPE_LABELS[space.parkingType]}
          {' · '}
          {space.vehicleSizes.map((v) => VEHICLE_SIZE_LABELS[v]).join(', ')}
          {space.covered && ' · Covered'}
          {space.evCharging && ' · EV charging'}
        </p>
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
