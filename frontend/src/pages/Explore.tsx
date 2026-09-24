import { useState } from 'react';

import { api } from '../api/client';
import { PublicSpace } from '../api/types';
import DestinationInput, { PickedLocation } from '../components/DestinationInput';
import MapView from '../components/MapView';
import SearchFilters, { DEFAULT_FILTERS, DiscoveryFilters } from '../components/SearchFilters';
import SpaceCard from '../components/SpaceCard';
import { roundUpToNextHalfHour, toLocalInputValue } from '../utils/dateInput';

/** Parse cents from a "12.50" dollars string; null when blank or invalid. */
function parseMaxPriceCents(text: string): number | null {
  const t = text.trim();
  if (t === '') return null;
  const dollars = Number(t);
  if (!Number.isFinite(dollars) || dollars < 0) return null;
  return Math.round(dollars * 100);
}

/**
 * Explore: plan-ahead discovery. Destination (address lookup) + date/time +
 * filters → privacy-safe nearby results on a map and in a list.
 * Public — no login required.
 */
export default function Explore() {
  const [location, setLocation] = useState<PickedLocation | null>(null);
  const [destText, setDestText] = useState('');
  const [arrival, setArrival] = useState(() =>
    toLocalInputValue(roundUpToNextHalfHour(new Date(Date.now() + 60 * 60 * 1000))),
  );
  const [departure, setDeparture] = useState(() =>
    toLocalInputValue(roundUpToNextHalfHour(new Date(Date.now() + 3 * 60 * 60 * 1000))),
  );
  const [filters, setFilters] = useState<DiscoveryFilters>(DEFAULT_FILTERS);
  const [results, setResults] = useState<PublicSpace[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validation, setValidation] = useState<string | null>(null);

  const onPick = (loc: PickedLocation) => {
    setLocation(loc);
    setDestText(loc.label);
  };

  const search = async () => {
    setValidation(null);
    setError(null);
    if (!location) {
      setValidation('Pick a destination from the suggestions first.');
      return;
    }
    const arrivalIso = new Date(arrival).toISOString();
    const departureIso = new Date(departure).toISOString();
    if (new Date(departureIso) <= new Date(arrivalIso)) {
      setValidation('Your departure has to be after your arrival.');
      return;
    }
    const maxPriceCents = parseMaxPriceCents(filters.maxPrice);
    if (filters.maxPrice.trim() !== '' && maxPriceCents == null) {
      setValidation('Max price must be a non-negative amount.');
      return;
    }
    setLoading(true);
    try {
      const res = await api.discovery.search({
        lat: location.lat,
        lng: location.lng,
        radiusMiles: filters.radiusMiles,
        arrival: arrivalIso,
        departure: departureIso,
        ...(maxPriceCents != null ? { maxPrice: maxPriceCents / 100 } : {}),
        ...(filters.covered ? { covered: true } : {}),
        ...(filters.evCharging ? { evCharging: true } : {}),
        ...(filters.vehicleSize ? { vehicleSize: filters.vehicleSize } : {}),
        size: 50,
      });
      setResults(res.results);
      setSelectedId(null);
      setSearched(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-900">Plan parking</h1>

      <DestinationInput value={destText} onPick={onPick} />

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor="arrival" className="text-sm font-medium text-slate-700">
            Arriving
          </label>
          <input
            id="arrival"
            type="datetime-local"
            value={arrival}
            onChange={(e) => setArrival(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
          />
        </div>
        <div>
          <label htmlFor="departure" className="text-sm font-medium text-slate-700">
            Leaving
          </label>
          <input
            id="departure"
            type="datetime-local"
            value={departure}
            onChange={(e) => setDeparture(e.target.value)}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
          />
        </div>
      </div>

      <SearchFilters filters={filters} onChange={setFilters} />

      {validation && (
        <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {validation}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <button
        type="button"
        onClick={search}
        disabled={loading}
        className="w-full rounded-lg bg-sky-600 py-3 font-semibold text-white disabled:opacity-60"
      >
        {loading ? 'Searching…' : 'Find parking'}
      </button>

      {!searched && !loading && (
        <p className="text-center text-sm text-slate-500">
          Enter a destination and when you&apos;ll park to see available shared spots nearby.
        </p>
      )}

      {searched && !loading && (
        <>
          {results.length === 0 ? (
            <div className="rounded-xl border border-slate-200 bg-white p-6 text-center">
              <p className="text-3xl" aria-hidden>🅿️</p>
              <p className="mt-2 font-semibold text-slate-800">No spots found</p>
              <p className="mt-1 text-sm text-slate-500">
                Try a wider radius, different times, or fewer filters — new shares appear all the
                time.
              </p>
            </div>
          ) : (
            <>
              <p className="text-sm text-slate-600" aria-live="polite">
                {results.length} {results.length === 1 ? 'spot' : 'spots'} near {location?.label}
              </p>
              <MapView
                spaces={results}
                center={[location!.lat, location!.lng]}
                selectedId={selectedId}
                onSelect={(s) => setSelectedId(s.id)}
              />
              <div className="space-y-3">
                {results.map((space) => (
                  <SpaceCard key={space.id} space={space} />
                ))}
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
