import { useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import { ApiError, GeocodeCandidate } from '../api/types';

export interface PickedLocation {
  lat: number;
  lng: number;
  label: string;
}

/**
 * Destination search box with address autocomplete (debounced, 3+ chars).
 * On a lookup failure it falls back gracefully — the typed text stays so the
 * user can try again, and the page notes that place search is unavailable.
 */
export default function DestinationInput({
  value,
  onPick,
  placeholder = 'Enter a destination — street, landmark, or neighborhood',
}: {
  value: string;
  onPick: (loc: PickedLocation) => void;
  placeholder?: string;
}) {
  const [text, setText] = useState(value);
  const [candidates, setCandidates] = useState<GeocodeCandidate[]>([]);
  const [open, setOpen] = useState(false);
  const [looking, setLooking] = useState(false);
  const [lookupFailed, setLookupFailed] = useState(false);
  const timer = useRef<number | null>(null);

  // Keep the visible text in sync when the parent resets it.
  useEffect(() => {
    setText(value);
  }, [value]);

  useEffect(
    () => () => {
      if (timer.current != null) window.clearTimeout(timer.current);
    },
    [],
  );

  const lookup = (query: string) => {
    if (timer.current != null) window.clearTimeout(timer.current);
    if (query.trim().length < 3) {
      setCandidates([]);
      setOpen(false);
      setLookupFailed(false);
      return;
    }
    timer.current = window.setTimeout(async () => {
      setLooking(true);
      setLookupFailed(false);
      try {
        const results = await api.discovery.geocode(query.trim());
        setCandidates(results.slice(0, 5));
        setOpen(results.length > 0);
      } catch (err) {
        // Lookup failure is non-fatal: keep the text, note the outage.
        setLookupFailed(true);
        setOpen(false);
        if (!(err instanceof ApiError)) console.error(err);
      } finally {
        setLooking(false);
      }
    }, 350);
  };

  const pick = (c: GeocodeCandidate) => {
    setText(c.displayName);
    setCandidates([]);
    setOpen(false);
    onPick({ lat: c.latitude, lng: c.longitude, label: c.displayName });
  };

  return (
    <div className="relative">
      <label htmlFor="destination" className="text-sm font-medium text-slate-700">
        Destination
      </label>
      <input
        id="destination"
        type="text"
        autoComplete="off"
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          lookup(e.target.value);
        }}
        onBlur={() => window.setTimeout(() => setOpen(false), 150)}
        onFocus={() => candidates.length > 0 && setOpen(true)}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
        role="combobox"
        aria-expanded={open}
        aria-controls="destination-suggestions"
        aria-autocomplete="list"
      />
      {looking && <p className="mt-1 text-xs text-slate-500">Looking up places…</p>}
      {lookupFailed && (
        <p className="mt-1 text-xs text-amber-700">
          Place search is unavailable right now — try a well-known landmark or check back shortly.
        </p>
      )}
      {open && (
        <ul
          id="destination-suggestions"
          role="listbox"
          className="absolute inset-x-0 top-full z-10 mt-1 max-h-56 overflow-auto rounded-lg border border-slate-200 bg-white shadow-lg"
        >
          {candidates.map((c) => (
            <li key={`${c.latitude},${c.longitude}:${c.displayName}`}>
              <button
                type="button"
                role="option"
                aria-selected={false}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(c)}
                className="block w-full px-3 py-2 text-left text-sm text-slate-700 hover:bg-sky-50"
              >
                📍 {c.displayName}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
