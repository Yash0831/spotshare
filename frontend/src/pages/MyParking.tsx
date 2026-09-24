import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api } from '../api/client';
import { PARKING_TYPE_LABELS, ParkingSpace, SessionExpiredError } from '../api/types';
import { useAuth } from '../auth/AuthContext';

/**
 * The host's "My Parking" surface: every space they own, with its status and
 * management actions. Phase 2 states are simple — OFFLINE (deactivated) or
 * PRIVATE (listed, not shared right now). AVAILABLE / RESERVED / RETURNING
 * derive from availability windows, which arrive in Phase 3.
 */
export default function MyParking() {
  const { user, loading: authLoading } = useAuth();
  const [spaces, setSpaces] = useState<ParkingSpace[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setSpaces(await api.spaces.mine());
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

  if (!authLoading && !user) return <Navigate to="/login" replace />;
  if (expired) return <Navigate to="/login" replace />;

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
                    className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ${
                      space.active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-600'
                    }`}
                  >
                    {space.active ? 'PRIVATE' : 'OFFLINE'}
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
    </div>
  );
}
