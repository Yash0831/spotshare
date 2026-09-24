import { Link, useLocation, useParams } from 'react-router-dom';

import { api } from '../api/client';
import { PARKING_TYPE_LABELS, PublicSpace, VEHICLE_SIZE_LABELS } from '../api/types';
import { formatCents, formatRate } from '../utils/money';
import { formatDateTime } from '../utils/time';

/**
 * Public detail for one discovery result. Shows everything the privacy-safe
 * DTO carries — approximate location, photos, price, availability, type,
 * vehicle fit, and the host's first name + last initial — and nothing else.
 *
 * There is no exact address on this page by design: it is revealed only
 * after a reservation is confirmed. The Reserve button carries the space
 * and the searched trip times into the booking flow.
 */
export default function PublicSpaceDetail() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const state = location.state as
    | { space?: PublicSpace; arrival?: string; departure?: string }
    | null;
  const space = state?.space;

  if (!space) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">That spot isn&apos;t loaded</p>
        <p className="text-sm text-slate-500">
          Spot details open from the Explore or Park Now results — the page needs a fresh search
          result to show.
        </p>
        <Link to="/explore" className="inline-block rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white">
          Back to Explore
        </Link>
      </div>
    );
  }

  // Sanity: the router state must match the URL — never trust state blindly.
  if (space.id !== id) {
    return (
      <div className="space-y-4 text-center">
        <p className="text-lg font-semibold text-slate-800">Something doesn&apos;t match</p>
        <Link to="/explore" className="inline-block rounded-lg bg-sky-600 px-4 py-2 font-semibold text-white">
          Back to Explore
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <Link to="/explore" className="text-sm font-medium text-sky-700">
        ← Back to results
      </Link>

      {space.photos.length > 0 ? (
        <div className="grid grid-cols-2 gap-2">
          {space.photos.slice(0, 4).map((photo) => (
            <img
              key={photo.id}
              src={api.photoUrl(photo)}
              alt={`${PARKING_TYPE_LABELS[space.parkingType]} parking space`}
              className="h-40 w-full rounded-lg object-cover"
              loading="lazy"
            decoding="async"
            />
          ))}
        </div>
      ) : (
        <div className="flex h-40 items-center justify-center rounded-lg bg-slate-100 text-4xl" aria-hidden>
          🅿️
        </div>
      )}

      <div>
        <div className="flex items-baseline justify-between gap-2">
          <h1 className="text-xl font-bold text-slate-900">{formatRate(space.hourlyRateCents)}</h1>
          <p className="text-sm font-medium text-slate-500">{space.distanceMiles.toFixed(1)} mi away</p>
        </div>
        <p className="mt-1 text-sm text-slate-600">
          {space.areaLabel}, {space.city}, {space.state} · approximate location
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <p className="text-sm text-slate-700">
          <span className="font-semibold">Available:</span>{' '}
          {formatDateTime(space.windowStartsAt)} – {formatDateTime(space.windowEndsAt)}
        </p>
        <p className="mt-1 text-sm text-slate-700">
          <span className="font-semibold">Your trip estimate:</span>{' '}
          {formatCents(space.estimatedTotalCents)} total
        </p>
      </div>

      <dl className="space-y-2 rounded-xl border border-slate-200 bg-white p-4 text-sm">
        <div className="flex justify-between">
          <dt className="text-slate-500">Type</dt>
          <dd className="font-medium text-slate-800">{PARKING_TYPE_LABELS[space.parkingType]}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Fits</dt>
          <dd className="font-medium text-slate-800">
            {space.vehicleSizes.map((v) => VEHICLE_SIZE_LABELS[v]).join(', ')}
          </dd>
        </div>
        {space.heightLimitInches != null && (
          <div className="flex justify-between">
            <dt className="text-slate-500">Height limit</dt>
            <dd className="font-medium text-slate-800">{space.heightLimitInches}&Prime;</dd>
          </div>
        )}
        <div className="flex justify-between">
          <dt className="text-slate-500">Covered</dt>
          <dd className="font-medium text-slate-800">{space.covered ? 'Yes' : 'No'}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">EV charging</dt>
          <dd className="font-medium text-slate-800">{space.evCharging ? 'Yes' : 'No'}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Host</dt>
          <dd className="font-medium text-slate-800">{space.hostName}</dd>
        </div>
      </dl>

      {space.description && <p className="text-sm text-slate-700">{space.description}</p>}

      <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
        <p className="text-sm text-slate-600">
          🔒 The exact address is shared only after you reserve — hosts list approximate
          locations to protect their privacy.
        </p>
        <Link
          to={`/spaces/${space.id}/reserve`}
          state={{ space, arrival: state?.arrival, departure: state?.departure }}
          className="mt-3 block w-full rounded-lg bg-sky-600 py-3 text-center font-semibold text-white"
        >
          Reserve this spot
        </Link>
        <p className="mt-2 text-center text-xs text-slate-500">
          Beta — no payment is collected. Your reservation holds the spot.
        </p>
      </div>
    </div>
  );
}
