import { useEffect, useState } from 'react';

import { api } from '../api/client';
import { PublicSpace } from '../api/types';
import DestinationInput, { PickedLocation } from '../components/DestinationInput';
import MapView from '../components/MapView';
import SpaceCard from '../components/SpaceCard';
import { useGeolocation } from '../hooks/useGeolocation';

const TWO_HOURS_MS = 2 * 60 * 60 * 1000;

/**
 * Park Now: "I'm driving right now" discovery. Geolocation → immediate search
 * for shares covering now → now+2h. If location is denied or unavailable, a
 * manual destination entry takes over — no dead end.
 * Public — no login required.
 */
export default function ParkNow() {
  const geo = useGeolocation();
  const [manual, setManual] = useState<PickedLocation | null>(null);
  const [destText, setDestText] = useState('');
  const [results, setResults] = useState<PublicSpace[]>([]);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [locateCount, setLocateCount] = useState(0);

  const searchAt = async (lat: number, lng: number) => {
    setError(null);
    setLoading(true);
    try {
      const arrival = new Date();
      const departure = new Date(arrival.getTime() + TWO_HOURS_MS);
      const res = await api.discovery.search({
        lat,
        lng,
        radiusMiles: 3,
        arrival: arrival.toISOString(),
        departure: departure.toISOString(),
        size: 50,
      });
      setResults(res.results);
      setSearched(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const locateAndSearch = () => {
    setSearched(false);
    setLocateCount((c) => c + 1);
    geo.locate();
  };

  // Once geolocation resolves after a tap, fire the search exactly once.
  useEffect(() => {
    if (geo.status === 'ready' && geo.position && locateCount > 0) {
      setLocateCount(0);
      void searchAt(geo.position.lat, geo.position.lng);
    }
    // searchAt is stable enough for this effect; intentionally not a dep.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [geo.status, geo.position, locateCount]);

  const manualSearch = () => {
    if (!manual) return;
    void searchAt(manual.lat, manual.lng);
  };

  const needsManual = geo.status === 'denied' || geo.status === 'unavailable' || geo.status === 'timeout';

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-900">Park now</h1>
      <p className="text-sm text-slate-600">
        Finds shared spots near you, available right now for the next 2 hours.
      </p>

      {!loading && geo.status !== 'locating' && (
        <button
          type="button"
          onClick={locateAndSearch}
          className="w-full rounded-xl bg-sky-600 py-4 text-lg font-semibold text-white disabled:opacity-60"
        >
          {searched ? '🔄 Search again near me' : '📍 Find parking near me'}
        </button>
      )}

      {geo.status === 'locating' && (
        <p className="rounded-lg bg-sky-50 px-3 py-2 text-center text-sm text-sky-800" aria-live="polite">
          Finding your location…
        </p>
      )}

      {needsManual && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
          <p className="text-sm font-medium text-amber-900">
            {geo.status === 'denied'
              ? 'Location access was declined — no problem.'
              : 'Your location isn’t available on this device.'}{' '}
            Enter a destination instead:
          </p>
          <div className="mt-2">
            <DestinationInput value={destText} onPick={(loc) => { setManual(loc); setDestText(loc.label); }} />
          </div>
          <button
            type="button"
            onClick={manualSearch}
            disabled={!manual || loading}
            className="mt-3 w-full rounded-lg bg-sky-600 py-2.5 font-semibold text-white disabled:opacity-60"
          >
            {loading ? 'Searching…' : 'Search near this destination'}
          </button>
        </div>
      )}

      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {loading && (
        <p className="text-center text-sm text-slate-500" aria-live="polite">
          Searching for nearby spots…
        </p>
      )}

      {searched && !loading && !error && (
        <>
          {results.length === 0 ? (
            <div className="rounded-xl border border-slate-200 bg-white p-6 text-center">
              <p className="text-3xl" aria-hidden>🅿️</p>
              <p className="mt-2 font-semibold text-slate-800">Nothing available right now</p>
              <p className="mt-1 text-sm text-slate-500">
                No shares cover the next 2 hours near you. Try again later or plan ahead from the
                Explore tab.
              </p>
            </div>
          ) : (
            <>
              <p className="text-sm text-slate-600" aria-live="polite">
                {results.length} {results.length === 1 ? 'spot' : 'spots'} available now, nearest first
              </p>
              <MapView
                spaces={results}
                center={
                  geo.position ? [geo.position.lat, geo.position.lng] : manual ? [manual.lat, manual.lng] : undefined
                }
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
