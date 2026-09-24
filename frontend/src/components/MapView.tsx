import { useEffect } from 'react';
import { MapContainer, Marker, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

import type { PublicSpace } from '../api/types';
import { formatRate } from '../utils/money';

/** Continental-US fallback when there is nothing to frame yet. */
export const US_CENTER: [number, number] = [39.8, -98.5];

function priceLabel(space: PublicSpace): string {
  return formatRate(space.hourlyRateCents);
}

function pinIcon(space: PublicSpace, selected: boolean): L.DivIcon {
  return L.divIcon({
    className: '',
    html: `<div class="price-pin${selected ? ' price-pin-selected' : ''}">${priceLabel(space)}</div>`,
    iconSize: [64, 30],
    iconAnchor: [32, 15],
  });
}

/** Reframes the map whenever the result set changes. */
function FitToResults({ spaces }: { spaces: PublicSpace[] }) {
  const map = useMap();
  useEffect(() => {
    if (spaces.length === 0) return;
    const bounds = L.latLngBounds(
      spaces.map((s) => [s.approxLatitude, s.approxLongitude] as [number, number]),
    );
    map.fitBounds(bounds.pad(0.25), { animate: false });
  }, [map, spaces]);
  return null;
}

interface MapViewProps {
  spaces: PublicSpace[];
  /** Center when there are no results yet (e.g. the searched point). */
  center?: [number, number];
  selectedId?: string | null;
  onSelect?: (space: PublicSpace) => void;
}

/**
 * Leaflet map with OpenStreetMap tiles — no API keys, no vendor lock-in.
 * Pins show the hourly price; locations are approximate by design (the
 * backend rounds coordinates to ~110 m and never exposes exact addresses).
 */
export default function MapView({ spaces, center = US_CENTER, selectedId, onSelect }: MapViewProps) {
  return (
    <MapContainer
      center={center}
      zoom={spaces.length > 0 ? 13 : 4}
      scrollWheelZoom={false}
      className="h-64 w-full sm:h-80"
      attributionControl
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitToResults spaces={spaces} />
      {spaces.map((space) => (
        <Marker
          key={space.id}
          position={[space.approxLatitude, space.approxLongitude]}
          icon={pinIcon(space, space.id === selectedId)}
          eventHandlers={{ click: () => onSelect?.(space) }}
        />
      ))}
    </MapContainer>
  );
}
