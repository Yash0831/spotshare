import { useState } from 'react';

import { VEHICLE_SIZE_LABELS, VehicleSize } from '../api/types';

export interface DiscoveryFilters {
  radiusMiles: number;
  maxPrice: string;
  covered: boolean;
  evCharging: boolean;
  vehicleSize: '' | VehicleSize;
}

export const DEFAULT_FILTERS: DiscoveryFilters = {
  radiusMiles: 3,
  maxPrice: '',
  covered: false,
  evCharging: false,
  vehicleSize: '',
};

const RADIUS_OPTIONS = [1, 3, 5, 10, 25];

/**
 * Collapsible filter strip for discovery search: radius, max hourly price,
 * covered, EV charging, and vehicle size. Everything here maps 1:1 onto the
 * backend's optional search parameters.
 */
export default function SearchFilters({
  filters,
  onChange,
}: {
  filters: DiscoveryFilters;
  onChange: (f: DiscoveryFilters) => void;
}) {
  const [open, setOpen] = useState(false);

  const set = <K extends keyof DiscoveryFilters>(key: K, value: DiscoveryFilters[K]) =>
    onChange({ ...filters, [key]: value });

  const activeCount =
    (filters.maxPrice !== '' ? 1 : 0) +
    (filters.covered ? 1 : 0) +
    (filters.evCharging ? 1 : 0) +
    (filters.vehicleSize !== '' ? 1 : 0);

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-center justify-between px-4 py-3 text-left"
      >
        <span className="text-sm font-semibold text-slate-800">
          Filters{activeCount > 0 && <span className="ml-1 text-sky-700">({activeCount})</span>}
        </span>
        <span aria-hidden className="text-slate-400">{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div className="space-y-4 border-t border-slate-100 px-4 py-4">
          <div>
            <label htmlFor="radius" className="text-sm font-medium text-slate-700">
              Search radius
            </label>
            <select
              id="radius"
              value={filters.radiusMiles}
              onChange={(e) => set('radiusMiles', Number(e.target.value))}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            >
              {RADIUS_OPTIONS.map((r) => (
                <option key={r} value={r}>
                  Within {r} {r === 1 ? 'mile' : 'miles'}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="maxPrice" className="text-sm font-medium text-slate-700">
              Max price per hour (free spaces always match)
            </label>
            <div className="mt-1 flex items-center">
              <span className="rounded-l-lg border border-r-0 border-slate-300 bg-slate-50 px-3 py-2 text-slate-500">
                $
              </span>
              <input
                id="maxPrice"
                type="number"
                min="0"
                step="0.5"
                inputMode="decimal"
                placeholder="No limit"
                value={filters.maxPrice}
                onChange={(e) => set('maxPrice', e.target.value)}
                className="w-full rounded-r-lg border border-slate-300 px-3 py-2"
              />
            </div>
          </div>

          <div>
            <label htmlFor="vehicleSize" className="text-sm font-medium text-slate-700">
              Vehicle size
            </label>
            <select
              id="vehicleSize"
              value={filters.vehicleSize}
              onChange={(e) => set('vehicleSize', e.target.value as DiscoveryFilters['vehicleSize'])}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            >
              <option value="">Any size</option>
              {Object.entries(VEHICLE_SIZE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-3">
            <label className="flex items-center gap-2 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={filters.covered}
                onChange={(e) => set('covered', e.target.checked)}
                className="h-4 w-4 accent-sky-600"
              />
              Covered parking
            </label>
            <label className="flex items-center gap-2 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={filters.evCharging}
                onChange={(e) => set('evCharging', e.target.checked)}
                className="h-4 w-4 accent-sky-600"
              />
              EV charging available
            </label>
          </div>
        </div>
      )}
    </div>
  );
}
