import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api } from '../api/client';
import {
  PARKING_TYPE_LABELS,
  AvailabilityWindow,
  DisplayState,
  ParkingSpace,
  SessionExpiredError,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { formatRate } from '../utils/money';
import { formatDateTime, formatTime } from '../utils/time';
import ReturnEarlyDialog from '../components/ReturnEarlyDialog';

/**
 * The host's "My Parking" surface: every space they own, with its derived
 * status (OFFLINE / PRIVATE / AVAILABLE / RETURNING) and management actions,
 * plus a cross-space list of current and upcoming shares.
 */
export default function MyParking() {
  const { user, loading: authLoading } = useAuth();
  const [spaces, setSpaces] = useState<ParkingSpace[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [shares, setShares] = useState<AvailabilityWindow[] | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [returnEarlyWindow, setReturnEarlyWindow] = useState<AvailabilityWindow | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [loadedSpaces, loadedShares] = await Promise.all([
        api.spaces.mine(),
        api.availability.mine(),
      ]);
      setSpaces(loadedSpaces);
      setShares(loadedShares);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not load your spaces. Please try again.');
    }
  }, []);

  useEffect(() => {
    if (!authLoading && user) void load();
  }, [authLoading, user, load]);

  async function deactivate(space: ParkingSpace) {
    if (confirmingId !== space.id) {
      setConfirmingId(space.id);
      return;
    }
    setConfirmingId(null);
    try {
      const updated = await api.spaces.deactivate(space.id);
      setSpaces((prev) => prev?.map((s) => (s.id === updated.id ? updated : s)) ?? null);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not deactivate the space. Please try again.');
    }
  }

  /** The shrunk window replaces the old one; an ended window drops out. */
  function handleReturnedEarly(updated: AvailabilityWindow) {
    setReturnEarlyWindow(null);
    setShares((prev) =>
      prev === null
        ? prev
        : prev
            .map((w) => (w.id === updated.id ? updated : w))
            .filter((w) => new Date(w.endsAt).getTime() > Date.now()),
    );
  }

  async function removeShare(windowId: string) {
    if (removingId !== windowId) {
      setRemovingId(windowId);
      return;
    }
    setRemovingId(null);
    try {
      await api.availability.remove(windowId);
      await load();
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not remove the share. Please try again.');
    }
  }

  if (!authLoading && !user) return <Navigate to="/login" replace />;
  if (expired) return <Navigate to="/login" replace />;

  const badgeCls: Record<DisplayState, string> = {
    OFFLINE: 'bg-slate-200 text-slate-600',
    PRIVATE: 'bg-emerald-100 text-emerald-800',
    AVAILABLE: 'bg-sky-100 text-sky-800',
    RETURNING: 'bg-amber-100 text-amber-800',
    RESERVED: 'bg-violet-100 text-violet-800',
  };

  const spaceLabel = (spaceId: string) =>
    spaces?.find((s) => s.id === spaceId)?.label ?? 'A space';

  return (
    <div className="mx-auto w-full max-w-md px-4 py-8">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-semibold uppercase tracking-widest text-sky-700">SpotShare</p>
          <h1 className="mt-1 text-2xl font-bold text-slate-900">My Parking</h1>
        </div>
        <Link to="/" className="text-sm font-semibold text-sky-700">
          Home
        </Link>
      </div>

      {error && (
        <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {shares !== null && shares.length > 0 && (
        <section aria-label="Currently sharing" className="mt-6">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
            Currently sharing
          </h2>
          <div className="mt-2 space-y-2">
            {shares.map((w) => (
              <div key={w.id} className="flex items-center justify-between rounded-xl border border-sky-200 bg-sky-50 p-4">
                <div>
                  <p className="text-sm font-bold text-sky-900">
                    {spaceLabel(w.spaceId)} · {w.live ? `Available until ${formatTime(w.endsAt)}` : `Shared until ${formatDateTime(w.endsAt)}`}
                  </p>
                  <p className="mt-0.5 text-sm text-sky-800">{formatRate(w.hourlyRateCents)}</p>
                </div>
                {!w.live ? (
                  <button
                    onClick={() => void removeShare(w.id)}
                    className={`inline-flex min-h-[44px] shrink-0 items-center rounded-lg px-3 py-2 text-sm font-semibold ${
                      removingId === w.id
                        ? 'bg-red-600 text-white'
                        : 'border border-slate-300 bg-white text-slate-700'
                    }`}
                  >
                    {removingId === w.id ? 'Tap again to remove' : 'Remove'}
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => setReturnEarlyWindow(w)}
                    className="inline-flex min-h-[44px] shrink-0 items-center rounded-lg border border-sky-300 bg-white px-3 py-2 text-sm font-semibold text-sky-800"
                  >
                    I&rsquo;m back early
                  </button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {spaces === null ? (
        <div className="mt-6 space-y-3" aria-label="Loading your spaces">
          {[0, 1].map((i) => (
            <div key={i} className="animate-pulse rounded-xl border border-slate-200 bg-white p-5">
              <div className="h-4 w-1/3 rounded bg-slate-200" />
              <div className="mt-2 h-3 w-2/3 rounded bg-slate-200" />
            </div>
          ))}
        </div>
      ) : spaces.length === 0 ? (
        <div className="mt-6 rounded-xl border border-dashed border-slate-300 bg-white p-8 text-center">
          <p className="text-lg font-semibold text-slate-900">No parking spaces yet</p>
          <p className="mt-2 text-sm text-slate-600">
            Set up your space once — it takes a few minutes — and you'll be able to share it
            whenever you leave.
          </p>
          <Link
            to="/parking/new"
            className="mt-5 block w-full rounded-lg bg-sky-700 px-4 py-3 text-center text-base font-semibold text-white shadow-sm"
          >
            Add your space
          </Link>
        </div>
      ) : (
        <div className="mt-6 space-y-4">
          {spaces.map((space) => (
            <article key={space.id} className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
              {space.photos[0] && (
                <img
                  src={api.photoUrl(space.photos[0])}
                  alt={`Photo of ${space.label}`}
                  className="h-40 w-full object-cover"
                  loading="lazy"
            decoding="async"
                />
              )}
              <div className="p-5">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h2 className="text-lg font-semibold text-slate-900">
                      {space.label} · {space.areaLabel}
                    </h2>
                    <p className="mt-0.5 text-sm text-slate-600">
                      {PARKING_TYPE_LABELS[space.parkingType]}
                    </p>
                  </div>
                  <span
                    className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ${badgeCls[space.displayState]}`}
                  >
                    {space.displayState}
                  </span>
                </div>
                <div className="mt-4 flex gap-2">
                  <Link
                    to={`/parking/${space.id}`}
                    className="flex-1 rounded-lg border border-slate-300 px-4 py-2.5 text-center text-sm font-semibold text-slate-700"
                  >
                    Manage
                  </Link>
                  {space.active && (
                    <button
                      onClick={() => void deactivate(space)}
                      className={`flex-1 rounded-lg px-4 py-2.5 text-sm font-semibold ${
                        confirmingId === space.id
                          ? 'bg-red-600 text-white'
                          : 'border border-red-200 text-red-700'
                      }`}
                    >
                      {confirmingId === space.id ? 'Tap again to confirm' : 'Deactivate'}
                    </button>
                  )}
                </div>
              </div>
            </article>
          ))}
          <Link
            to="/parking/new"
            className="block w-full rounded-lg border border-dashed border-slate-300 bg-white px-4 py-3 text-center text-base font-semibold text-sky-700"
          >
            + Add another space
          </Link>
        </div>
      )}

      {returnEarlyWindow && (
        <ReturnEarlyDialog
          window={returnEarlyWindow}
          spaceLabel={spaceLabel(returnEarlyWindow.spaceId)}
          onClose={() => setReturnEarlyWindow(null)}
          onUpdated={handleReturnedEarly}
          onSessionExpired={() => setExpired(true)}
        />
      )}
    </div>
  );
}
