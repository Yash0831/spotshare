import { PublicSpace } from '../api/types';

/** A privacy-safe discovery result, matching the backend's PublicSpaceDto. */
export const publicSpaceFixture: PublicSpace = {
  id: 'space-1',
  areaLabel: 'West Loop',
  city: 'Chicago',
  state: 'IL',
  parkingType: 'ASSIGNED_SPACE',
  description: 'Quiet spot behind the building',
  vehicleSizes: ['SEDAN', 'SUV'],
  heightLimitInches: null,
  covered: true,
  evCharging: false,
  approxLatitude: 41.886,
  approxLongitude: -87.619,
  distanceMiles: 0.2,
  hostName: 'Michael R.',
  hourlyRateCents: 300,
  estimatedTotalCents: 600,
  windowStartsAt: '2026-09-24T15:00:00Z',
  windowEndsAt: '2026-09-24T19:00:00Z',
  photos: [],
};
