import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import {
  PARKING_TYPE_LABELS,
  ParkingType,
  SessionExpiredError,
  VEHICLE_SIZE_LABELS,
  VehicleSize,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';

const STEPS = ['Location', 'Parking type', 'Photos', 'Instructions', 'Authorization'] as const;

const PARKING_TYPES = Object.keys(PARKING_TYPE_LABELS) as ParkingType[];
const VEHICLE_SIZES = Object.keys(VEHICLE_SIZE_LABELS) as VehicleSize[];
const MAX_PHOTOS = 8;
const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

const AUTHORIZATION_TEXT =
  'I confirm that I own, control, or have permission to share this parking space.';

interface Fields {
  label: string;
  address: string;
  city: string;
  state: string;
  zipCode: string;
  latitude: string;
  longitude: string;
  areaLabel: string;
  parkingType: ParkingType | '';
  vehicleSizes: VehicleSize[];
  heightLimitInches: string;
  covered: boolean;
  evCharging: boolean;
  description: string;
  parkingInstructions: string;
  authorizationConfirmed: boolean;
}

const EMPTY: Fields = {
  label: '',
  address: '',
  city: '',
  state: '',
  zipCode: '',
  latitude: '',
  longitude: '',
  areaLabel: '',
  parkingType: '',
  vehicleSizes: [],
  heightLimitInches: '',
  covered: false,
  evCharging: false,
  description: '',
  parkingInstructions: '',
  authorizationConfirmed: false,
};

interface PhotoPick {
  file: File;
  preview: string;
}

/**
 * Host space setup: Location → Parking type → Photos → Instructions →
 * Authorization. The space is created once and reused for every future
 * "I'm leaving" share (Phase 3).
 */
export default function SpaceWizard() {
  const { user, loading: authLoading } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [fields, setFields] = useState<Fields>(EMPTY);
  const [photos, setPhotos] = useState<PhotoPick[]>([]);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [createdSpaceId, setCreatedSpaceId] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);

  if (!authLoading && !user) return <Navigate to="/login" replace />;
  if (expired) return <Navigate to="/login" replace />;

  function set<K extends keyof Fields>(key: K, value: Fields[K]) {
    setFields((f) => ({ ...f, [key]: value }));
    setFieldErrors((e) => {
      const next = { ...e };
      delete next[key];
      return next;
    });
  }

  function validateStep(): boolean {
    const errs: Record<string, string> = {};
    if (step === 0) {
      if (!fields.label.trim()) errs.label = 'Give your space a short label, like a space number.';
      if (!fields.address.trim()) errs.address = 'Enter the street address of your space.';
      if (!fields.city.trim()) errs.city = 'Enter the city.';
      if (!/^[A-Za-z]{2}$/.test(fields.state.trim()))
        errs.state = 'Enter the 2-letter state code, e.g. IL.';
      if (!/^\d{5}(-\d{4})?$/.test(fields.zipCode.trim()))
        errs.zipCode = 'Enter a valid ZIP code, e.g. 60601.';
      const lat = Number(fields.latitude);
      const lng = Number(fields.longitude);
      if (fields.latitude.trim() === '' || Number.isNaN(lat) || lat < -90 || lat > 90)
        errs.latitude = 'Enter a latitude between -90 and 90.';
      if (fields.longitude.trim() === '' || Number.isNaN(lng) || lng < -180 || lng > 180)
        errs.longitude = 'Enter a longitude between -180 and 180.';
      if (!fields.areaLabel.trim())
        errs.areaLabel = 'Enter the area name shown publicly, e.g. West Loop.';
    }
    if (step === 1) {
      if (!fields.parkingType) errs.parkingType = 'Choose the type of parking space.';
      if (fields.vehicleSizes.length === 0)
        errs.vehicleSizes = 'Select at least one vehicle size that fits.';
      if (fields.heightLimitInches.trim() !== '') {
        const h = Number(fields.heightLimitInches);
        if (!Number.isInteger(h) || h <= 0)
          errs.heightLimitInches = 'Enter a whole number of inches, e.g. 84.';
      }
    }
    if (step === 4 && !fields.authorizationConfirmed) {
      errs.authorizationConfirmed = 'Please confirm to continue.';
    }
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function next() {
    if (validateStep()) {
      setSubmitError(null);
      setStep((s) => Math.min(s + 1, STEPS.length - 1));
      window.scrollTo(0, 0);
    }
  }

  function back() {
    setSubmitError(null);
    setStep((s) => Math.max(s - 1, 0));
    window.scrollTo(0, 0);
  }

  function addPhotos(files: FileList | null) {
    if (!files) return;
    const picked: PhotoPick[] = [];
    for (const file of Array.from(files)) {
      if (photos.length + picked.length >= MAX_PHOTOS) break;
      if (!ALLOWED_TYPES.includes(file.type) || file.size > MAX_PHOTO_BYTES) continue;
      picked.push({ file, preview: URL.createObjectURL(file) });
    }
    if (picked.length > 0) setPhotos((p) => [...p, ...picked]);
  }

  function removePhoto(index: number) {
    setPhotos((p) => {
      URL.revokeObjectURL(p[index].preview);
      return p.filter((_, i) => i !== index);
    });
  }

  async function submit() {
    if (!validateStep() || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      // Create the space first (photos need the space id). If a previous
      // attempt created the space but failed on photos, don't create a
      // duplicate — just retry the uploads.
      let spaceId = createdSpaceId;
      if (!spaceId) {
        const height = fields.heightLimitInches.trim();
        const created = await api.spaces.create({
          label: fields.label.trim(),
          address: fields.address.trim(),
          city: fields.city.trim(),
          state: fields.state.trim(),
          zipCode: fields.zipCode.trim(),
          latitude: Number(fields.latitude),
          longitude: Number(fields.longitude),
          areaLabel: fields.areaLabel.trim(),
          parkingType: fields.parkingType as ParkingType,
          description: fields.description.trim() || undefined,
          vehicleSizes: fields.vehicleSizes,
          heightLimitInches: height ? Number(height) : undefined,
          covered: fields.covered,
          evCharging: fields.evCharging,
          parkingInstructions: fields.parkingInstructions.trim() || undefined,
          authorizationConfirmed: true,
        });
        spaceId = created.id;
        setCreatedSpaceId(spaceId);
      }
      for (const photo of photos) {
        await api.spaces.uploadPhoto(spaceId, photo.file);
      }
      navigate('/parking', { replace: true });
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        setExpired(true);
        return;
      }
      setSubmitError(
        e instanceof Error ? e.message : 'Something went wrong. Please try again.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  const inputCls =
    'w-full rounded-lg border border-slate-300 bg-white px-4 py-3 text-base text-slate-900 placeholder:text-slate-400 focus:border-sky-600 focus:outline-none';
  const labelCls = 'mb-1 block text-sm font-semibold text-slate-700';
  const errCls = 'mt-1 text-sm text-red-600';

  return (
    <div className="mx-auto w-full max-w-md px-4 py-8">
      <p className="text-sm font-semibold uppercase tracking-widest text-sky-700">SpotShare</p>
      <h1 className="mt-1 text-2xl font-bold text-slate-900">Set up your space</h1>

      <ol className="mt-5 flex gap-1" aria-label="Setup progress">
        {STEPS.map((name, i) => (
          <li key={name} className="flex-1" title={name}>
            <div
              className={`h-1.5 rounded-full ${i <= step ? 'bg-sky-700' : 'bg-slate-200'}`}
            />
          </li>
        ))}
      </ol>
      <p className="mt-2 text-sm font-medium text-slate-600">
        Step {step + 1} of {STEPS.length}: {STEPS[step]}
      </p>

      {submitError && (
        <div role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {submitError}
        </div>
      )}

      <div className="mt-5">
        {step === 0 && (
          <div className="space-y-4">
            <div>
              <label className={labelCls} htmlFor="label">Space label or number</label>
              <input
                id="label" className={inputCls} placeholder="B17" value={fields.label}
                onChange={(e) => set('label', e.target.value)} autoComplete="off"
              />
              {fieldErrors.label && <p className={errCls}>{fieldErrors.label}</p>}
            </div>
            <div>
              <label className={labelCls} htmlFor="address">Street address</label>
              <input
                id="address" className={inputCls} placeholder="123 Wacker Dr" value={fields.address}
                onChange={(e) => set('address', e.target.value)} autoComplete="street-address"
              />
              <p className="mt-1 text-xs text-slate-500">
                Only you see the exact address. Drivers see your area name until they reserve.
              </p>
              {fieldErrors.address && <p className={errCls}>{fieldErrors.address}</p>}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls} htmlFor="city">City</label>
                <input
                  id="city" className={inputCls} placeholder="Chicago" value={fields.city}
                  onChange={(e) => set('city', e.target.value)} autoComplete="address-level2"
                />
                {fieldErrors.city && <p className={errCls}>{fieldErrors.city}</p>}
              </div>
              <div>
                <label className={labelCls} htmlFor="state">State</label>
                <input
                  id="state" className={inputCls} placeholder="IL" maxLength={2} value={fields.state}
                  onChange={(e) => set('state', e.target.value)} autoComplete="address-level1"
                />
                {fieldErrors.state && <p className={errCls}>{fieldErrors.state}</p>}
              </div>
            </div>
            <div>
              <label className={labelCls} htmlFor="zip">ZIP code</label>
              <input
                id="zip" className={inputCls} placeholder="60601" inputMode="numeric"
                value={fields.zipCode} onChange={(e) => set('zipCode', e.target.value)}
                autoComplete="postal-code"
              />
              {fieldErrors.zipCode && <p className={errCls}>{fieldErrors.zipCode}</p>}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={labelCls} htmlFor="lat">Latitude</label>
                <input
                  id="lat" className={inputCls} placeholder="41.8858" inputMode="decimal"
                  value={fields.latitude} onChange={(e) => set('latitude', e.target.value)}
                />
                {fieldErrors.latitude && <p className={errCls}>{fieldErrors.latitude}</p>}
              </div>
              <div>
                <label className={labelCls} htmlFor="lng">Longitude</label>
                <input
                  id="lng" className={inputCls} placeholder="-87.6189" inputMode="decimal"
                  value={fields.longitude} onChange={(e) => set('longitude', e.target.value)}
                />
                {fieldErrors.longitude && <p className={errCls}>{fieldErrors.longitude}</p>}
              </div>
            </div>
            <p className="-mt-2 text-xs text-slate-500">
              Find these in any maps app by dropping a pin on your space.
            </p>
            <div>
              <label className={labelCls} htmlFor="area">Public area name</label>
              <input
                id="area" className={inputCls} placeholder="West Loop" value={fields.areaLabel}
                onChange={(e) => set('areaLabel', e.target.value)}
              />
              <p className="mt-1 text-xs text-slate-500">
                Shown publicly instead of your exact address, e.g. "West Loop, Chicago, IL".
              </p>
              {fieldErrors.areaLabel && <p className={errCls}>{fieldErrors.areaLabel}</p>}
            </div>
          </div>
        )}

        {step === 1 && (
          <div className="space-y-5">
            <fieldset>
              <legend className={labelCls}>What kind of space is it?</legend>
              <div className="grid grid-cols-2 gap-2">
                {PARKING_TYPES.map((t) => (
                  <button
                    key={t} type="button" aria-pressed={fields.parkingType === t}
                    onClick={() => set('parkingType', t)}
                    className={`rounded-lg border px-3 py-3 text-sm font-semibold ${
                      fields.parkingType === t
                        ? 'border-sky-700 bg-sky-50 text-sky-800'
                        : 'border-slate-300 bg-white text-slate-700'
                    }`}
                  >
                    {PARKING_TYPE_LABELS[t]}
                  </button>
                ))}
              </div>
              {fieldErrors.parkingType && <p className={errCls}>{fieldErrors.parkingType}</p>}
            </fieldset>
            <fieldset>
              <legend className={labelCls}>What fits?</legend>
              <div className="flex flex-wrap gap-2">
                {VEHICLE_SIZES.map((v) => {
                  const on = fields.vehicleSizes.includes(v);
                  return (
                    <button
                      key={v} type="button" aria-pressed={on}
                      onClick={() =>
                        set(
                          'vehicleSizes',
                          on
                            ? fields.vehicleSizes.filter((x) => x !== v)
                            : [...fields.vehicleSizes, v],
                        )
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
              {fieldErrors.vehicleSizes && <p className={errCls}>{fieldErrors.vehicleSizes}</p>}
            </fieldset>
            <div>
              <label className={labelCls} htmlFor="height">Height limit (inches, optional)</label>
              <input
                id="height" className={inputCls} placeholder="84" inputMode="numeric"
                value={fields.heightLimitInches}
                onChange={(e) => set('heightLimitInches', e.target.value)}
              />
              {fieldErrors.heightLimitInches && (
                <p className={errCls}>{fieldErrors.heightLimitInches}</p>
              )}
            </div>
            <div className="space-y-3">
              <label className="flex items-center justify-between rounded-lg border border-slate-300 bg-white px-4 py-3">
                <span className="text-sm font-semibold text-slate-700">Covered</span>
                <input
                  type="checkbox" className="h-5 w-5 accent-sky-700" checked={fields.covered}
                  onChange={(e) => set('covered', e.target.checked)}
                />
              </label>
              <label className="flex items-center justify-between rounded-lg border border-slate-300 bg-white px-4 py-3">
                <span className="text-sm font-semibold text-slate-700">EV charging available</span>
                <input
                  type="checkbox" className="h-5 w-5 accent-sky-700" checked={fields.evCharging}
                  onChange={(e) => set('evCharging', e.target.checked)}
                />
              </label>
            </div>
          </div>
        )}

        {step === 2 && (
          <div>
            <p className="text-sm text-slate-600">
              Add up to {MAX_PHOTOS} photos so drivers recognize the spot. JPEG, PNG, or WebP,
              under 5 MB each. You can skip this for now and add photos later.
            </p>
            <label className="mt-4 block cursor-pointer rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center text-sm font-semibold text-sky-700">
              Choose photos
              <input
                type="file" accept="image/jpeg,image/png,image/webp" multiple
                className="hidden" onChange={(e) => addPhotos(e.target.files)}
              />
            </label>
            {photos.length > 0 && (
              <div className="mt-4 grid grid-cols-3 gap-2">
                {photos.map((p, i) => (
                  <div key={p.preview} className="relative">
                    <img
                      src={p.preview} alt={`Selected photo ${i + 1}`}
                      className="h-24 w-full rounded-lg object-cover"
                    />
                    <button
                      type="button" onClick={() => removePhoto(i)} aria-label={`Remove photo ${i + 1}`}
                      className="absolute right-1 top-1 rounded-full bg-slate-900/70 px-2 py-0.5 text-xs font-bold text-white"
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {step === 3 && (
          <div className="space-y-4">
            <div>
              <label className={labelCls} htmlFor="desc">Description (optional)</label>
              <textarea
                id="desc" rows={3} className={inputCls}
                placeholder="Second level of the garage, near the elevators."
                value={fields.description} onChange={(e) => set('description', e.target.value)}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor="instr">Parking & access instructions</label>
              <textarea
                id="instr" rows={4} className={inputCls}
                placeholder="Gate code 1234. Park in B17 — it's the third spot on the right."
                value={fields.parkingInstructions}
                onChange={(e) => set('parkingInstructions', e.target.value)}
              />
              <p className="mt-1 text-xs text-slate-500">
                Only shown to you and to drivers after they reserve. Include anything a
                stranger needs to park without bothering you.
              </p>
            </div>
          </div>
        )}

        {step === 4 && (
          <div>
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-5">
              <p className="text-sm font-semibold text-slate-900">Please read before sharing</p>
              <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-700">
                <li>Only share spaces you control — never public street parking, fire lanes, or someone else's assigned spot.</li>
                <li>Your exact address and instructions are only revealed to drivers after they reserve.</li>
              </ul>
            </div>
            <label className="mt-4 flex cursor-pointer items-start gap-3 rounded-xl border border-slate-300 bg-white p-5">
              <input
                type="checkbox" className="mt-0.5 h-5 w-5 shrink-0 accent-sky-700"
                checked={fields.authorizationConfirmed}
                onChange={(e) => set('authorizationConfirmed', e.target.checked)}
              />
              <span className="text-sm font-medium text-slate-800">{AUTHORIZATION_TEXT}</span>
            </label>
            {fieldErrors.authorizationConfirmed && (
              <p className={errCls}>{fieldErrors.authorizationConfirmed}</p>
            )}
          </div>
        )}
      </div>

      <div className="mt-6 flex gap-3">
        {step > 0 && (
          <button
            onClick={back} disabled={submitting}
            className="rounded-lg border border-slate-300 px-5 py-3 text-base font-semibold text-slate-700 disabled:opacity-60"
          >
            Back
          </button>
        )}
        {step < STEPS.length - 1 ? (
          <button
            onClick={next}
            className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-semibold text-white shadow-sm"
          >
            Continue
          </button>
        ) : (
          <button
            onClick={() => void submit()} disabled={submitting}
            className="flex-1 rounded-lg bg-sky-700 px-4 py-3 text-base font-semibold text-white shadow-sm disabled:opacity-60"
          >
            {submitting ? 'Saving…' : createdSpaceId ? 'Retry photo upload' : 'Create my space'}
          </button>
        )}
      </div>
    </div>
  );
}
