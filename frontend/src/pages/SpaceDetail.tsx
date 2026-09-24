import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import {
  PARKING_TYPE_LABELS,
  ParkingSpace,
  ParkingType,
  SessionExpiredError,
  UpdateSpacePayload,
  VEHICLE_SIZE_LABELS,
  VehicleSize,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';

const PARKING_TYPES = Object.keys(PARKING_TYPE_LABELS) as ParkingType[];
const VEHICLE_SIZES = Object.keys(VEHICLE_SIZE_LABELS) as VehicleSize[];
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_PHOTO_BYTES = 5 * 1024 * 1024;

/**
 * Owner management view for one space: photos, details (including the
 * private exact address — only the host ever sees this page), editing,
 * and deactivation.
 */
export default function SpaceDetail() {
  const { id } = useParams<{ id: string }>();
  const { user, loading: authLoading } = useAuth();
  const navigate = useNavigate();
  const [space, setSpace] = useState<ParkingSpace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<UpdateSpacePayload | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [confirmingDeactivate, setConfirmingDeactivate] = useState(false);
  const [expired, setExpired] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setError(null);
    try {
      setSpace(await api.spaces.get(id));
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      if (e instanceof Error && 'status' in e && (e as { status: number }).status === 404) {
        setNotFound(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not load the space. Please try again.');
    }
  }, [id]);

  useEffect(() => {
    if (!authLoading && user) void load();
  }, [authLoading, user, load]);

  if (!authLoading && !user) return <Navigate to="/login" replace />;
  if (expired) return <Navigate to="/login" replace />;

  function startEdit() {
    if (!space) return;
    setDraft({
      label: space.label,
      address: space.address,
      city: space.city,
      state: space.state,
      zipCode: space.zipCode,
      latitude: space.latitude,
      longitude: space.longitude,
      areaLabel: space.areaLabel,
      parkingType: space.parkingType,
      description: space.description ?? undefined,
      vehicleSizes: space.vehicleSizes,
      heightLimitInches: space.heightLimitInches ?? undefined,
      covered: space.covered,
      evCharging: space.evCharging,
      parkingInstructions: space.parkingInstructions ?? undefined,
    });
    setEditing(true);
    window.scrollTo(0, 0);
  }

  async function saveEdit() {
    if (!space || !draft || saving) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await api.spaces.update(space.id, draft);
      setSpace(updated);
      setEditing(false);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not save. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  async function deactivate() {
    if (!space) return;
    if (!confirmingDeactivate) {
      setConfirmingDeactivate(true);
      return;
    }
    try {
      setSpace(await api.spaces.deactivate(space.id));
      setConfirmingDeactivate(false);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not deactivate. Please try again.');
    }
  }

  async function addPhotos(files: FileList | null) {
    if (!space || !files || uploading) return;
    const valid = Array.from(files).filter(
      (f) => ALLOWED_TYPES.includes(f.type) && f.size <= MAX_PHOTO_BYTES,
    );
    if (valid.length === 0) {
      setError('Please choose JPEG, PNG, or WebP photos under 5 MB each.');
      return;
    }
    setUploading(true);
    setError(null);
    try {
      for (const file of valid) {
        await api.spaces.uploadPhoto(space.id, file);
      }
      await load();
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setError(e instanceof Error ? e.message : 'Could not upload the photo. Please try again.');
    } finally {
      setUploading(false);
    }
  }

  async function deletePhoto(photoId: string) {
    if (!space) return;
    try {
      await api.spaces.deletePhoto(space.id, photoId);
      setSpace({ ...space, photos: space.photos.filter((p) => p.id !== photoId) });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not delete the photo. Please try again.');
    }
  }

  const inputCls =
    'w-full rounded-lg border border-slate-300 bg-white px-4 py-3 text-base text-slate-900 focus:border-sky-600 focus:outline-none';
  const labelCls = 'mb-1 block text-sm font-semibold text-slate-700';

  return (
    <div className="mx-auto w-full max-w-md px-4 py-8">
      <Link to="/parking" className="text-sm font-semibold text-sky-700">
        ← My Parking
      </Link>

      {error && (
        <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {notFound ? (
        <div className="mt-6 rounded-xl border border-slate-200 bg-white p-8 text-center">
          <p className="text-lg font-semibold text-slate-900">Space not found</p>
          <p className="mt-2 text-sm text-slate-600">
            It may have been removed, or it belongs to another account.
          </p>
        </div>
      ) : !space ? (
        <div className="mt-6 animate-pulse rounded-xl border border-slate-200 bg-white p-5" aria-label="Loading space">
          <div className="h-4 w-1/3 rounded bg-slate-200" />
          <div className="mt-2 h-3 w-2/3 rounded bg-slate-200" />
        </div>
      ) : editing && draft ? (
        <div className="mt-4">
          <h1 className="text-2xl font-bold text-slate-900">Edit space</h1>
          <div className="mt-5 space-y-4">
            <div>
              <label className={labelCls} htmlFor="label">Space label or number</label>
              <input
                id="label" className={inputCls} value={draft.label}
                onChange={(e) => setDraft({ ...draft, label: e.target.value })}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="address">Street address</label>
              <input
                id="address" className={inputCls} value={draft.address}
                onChange={(e) => setDraft({ ...draft, address: e.target.value })}
              />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div className="col-span-1">
                <label className={labelCls} htmlFor="city">City</label>
                <input
                  id="city" className={inputCls} value={draft.city}
                  onChange={(e) => setDraft({ ...draft, city: e.target.value })}
                />
              </div>
              <div>
                <label className={labelCls} htmlFor="state">State</label>
                <input
                  id="state" className={inputCls} maxLength={2} value={draft.state}
                  onChange={(e) => setDraft({ ...draft, state: e.target.value })}
                />
              </div>
              <div>
                <label className={labelCls} htmlFor="zip">ZIP</label>
                <input
                  id="zip" className={inputCls} value={draft.zipCode}
                  onChange={(e) => setDraft({ ...draft, zipCode: e.target.value })}
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls} htmlFor="lat">Latitude</label>
                <input
                  id="lat" className={inputCls} inputMode="decimal" value={draft.latitude}
                  onChange={(e) => setDraft({ ...draft, latitude: Number(e.target.value) })}
                />
              </div>
              <div>
                <label className={labelCls} htmlFor="lng">Longitude</label>
                <input
                  id="lng" className={inputCls} inputMode="decimal" value={draft.longitude}
                  onChange={(e) => setDraft({ ...draft, longitude: Number(e.target.value) })}
                />
              </div>
            </div>
            <div>
              <label className={labelCls} htmlFor="area">Public area name</label>
              <input
                id="area" className={inputCls} value={draft.areaLabel}
                onChange={(e) => setDraft({ ...draft, areaLabel: e.target.value })}
              />
            </div>
            <fieldset>
              <legend className={labelCls}>Parking type</legend>
              <div className="grid grid-cols-2 gap-2">
                {PARKING_TYPES.map((t) => (
                  <button
                    key={t} type="button" aria-pressed={draft.parkingType === t}
                    onClick={() => setDraft({ ...draft, parkingType: t })}
                    className={`rounded-lg border px-3 py-2.5 text-sm font-semibold ${
                      draft.parkingType === t
                        ? 'border-sky-700 bg-sky-50 text-sky-800'
                        : 'border-slate-300 bg-white text-slate-700'
                    }`}
                  >
                    {PARKING_TYPE_LABELS[t]}
                  </button>
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className={labelCls}>What fits?</legend>
              <div className="flex flex-wrap gap-2">
                {VEHICLE_SIZES.map((v) => {
                  const on = draft.vehicleSizes.includes(v);
                  return (
                    <button
                      key={v} type="button" aria-pressed={on}
                      onClick={() =>
                        setDraft({
                          ...draft,
                          vehicleSizes: on
                            ? draft.vehicleSizes.filter((x) => x !== v)
                            : [...draft.vehicleSizes, v],
                        })
                      }
                      className={`rounded-full border px-4 py-2 text-sm font-semibold ${
                        on
                          ? 'border-sky-700 bg-sky-50 text-sky-800'
                          : 'border-slate-300 bg-white text-slate-700'
                      }`}
                    >
                      {VEHICLE_SIZE_LABELS[v]}
                    </button>
                  );
                })}
              </div>
            </fieldset>
            <div>
              <label className={labelCls} htmlFor="instr">Parking & access instructions</label>
              <textarea
                id="instr" rows={3} className={inputCls}
                value={draft.parkingInstructions ?? ''}
                onChange={(e) => setDraft({ ...draft, parkingInstructions: e.target.value })}
              />
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => setEditing(false)} disabled={saving}
                className="rounded-lg border border-slate-300 px-5 py-3 text-base font-semibold text-slate-700 disabled:opacity-60"
              >
                Cancel
              </button>
              <button
                onClick={() => void saveEdit()} disabled={saving}
                className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-semibold text-white shadow-sm disabled:opacity-60"
              >
                {saving ? 'Saving…' : 'Save changes'}
              </button>
            </div>
          </div>
        </div>
      ) : (
        <div className="mt-4">
          <div className="flex items-start justify-between gap-3">
            <h1 className="text-2xl font-bold text-slate-900">
              {space.label} · {space.areaLabel}
            </h1>
            <span
              className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold ${
                space.active ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-600'
              }`}
            >
              {space.active ? 'PRIVATE' : 'OFFLINE'}
            </span>
          </div>
          <p className="mt-1 text-sm text-slate-600">{PARKING_TYPE_LABELS[space.parkingType]}</p>

          <div className="mt-5">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Photos</h2>
              <label className="cursor-pointer text-sm font-semibold text-sky-700">
                {uploading ? 'Uploading…' : '+ Add'}
                <input
                  type="file" accept="image/jpeg,image/png,image/webp" multiple
                  className="hidden" disabled={uploading}
                  onChange={(e) => void addPhotos(e.target.files)}
                />
              </label>
            </div>
            {space.photos.length === 0 ? (
              <p className="mt-2 text-sm text-slate-500">No photos yet.</p>
            ) : (
              <div className="mt-2 grid grid-cols-3 gap-2">
                {space.photos.map((p, i) => (
                  <div key={p.id} className="relative">
                    <img
                      src={api.photoUrl(p)} alt={`Photo ${i + 1} of ${space.label}`}
                      className="h-24 w-full rounded-lg object-cover" loading="lazy"
                    />
                    <button
                      onClick={() => void deletePhoto(p.id)} aria-label={`Delete photo ${i + 1}`}
                      className="absolute right-1 top-1 rounded-full bg-slate-900/70 px-2 py-0.5 text-xs font-bold text-white"
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <dl className="mt-5 space-y-3 rounded-xl border border-slate-200 bg-white p-5 text-sm">
            <div>
              <dt className="font-semibold text-slate-500">Exact address</dt>
              <dd className="mt-0.5 text-slate-900">
                {space.address}, {space.city}, {space.state} {space.zipCode}
              </dd>
              <dd className="mt-0.5 text-xs text-slate-500">Only you can see this.</dd>
            </div>
            <div>
              <dt className="font-semibold text-slate-500">Parking instructions</dt>
              <dd className="mt-0.5 text-slate-900">{space.parkingInstructions ?? '—'}</dd>
            </div>
            <div>
              <dt className="font-semibold text-slate-500">Details</dt>
              <dd className="mt-0.5 text-slate-900">
                {space.vehicleSizes.map((v) => VEHICLE_SIZE_LABELS[v]).join(' · ')}
                {space.heightLimitInches ? ` · ${space.heightLimitInches}″ max height` : ''}
                {space.covered ? ' · Covered' : ''}
                {space.evCharging ? ' · EV charging' : ''}
              </dd>
            </div>
            {space.description && (
              <div>
                <dt className="font-semibold text-slate-500">Description</dt>
                <dd className="mt-0.5 text-slate-900">{space.description}</dd>
              </div>
            )}
          </dl>

          <div className="mt-5 space-y-2">
            <button
              onClick={startEdit}
              className="block w-full rounded-lg border border-slate-300 px-4 py-3 text-center text-base font-semibold text-slate-700"
            >
              Edit space
            </button>
            {space.active && (
              <button
                onClick={() => void deactivate()}
                className={`block w-full rounded-lg px-4 py-3 text-base font-semibold ${
                  confirmingDeactivate ? 'bg-red-600 text-white' : 'border border-red-200 text-red-700'
                }`}
              >
                {confirmingDeactivate ? 'Tap again to deactivate' : 'Deactivate space'}
              </button>
            )}
            {!space.active && (
              <button
                onClick={() => navigate('/parking')}
                className="block w-full rounded-lg border border-slate-300 px-4 py-3 text-center text-base font-semibold text-slate-700"
              >
                Back to My Parking
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
