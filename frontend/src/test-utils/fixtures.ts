import { PublicSpace, Reservation, ReservationDetail } from '../api/types';

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

/** A confirmed reservation summary — no exact address, by design. */
export const reservationFixture: Reservation = {
  id: 'res-1',
  code: 'SP-K84D2',
  status: 'CONFIRMED',
  arrival: '2026-09-24T16:00:00Z',
  departure: '2026-09-24T18:00:00Z',
  hourlyRateCents: 300,
  totalCents: 600,
  spaceId: 'space-1',
  spaceTypeLabel: 'Assigned space',
  hostName: 'Michael R.',
  createdAt: '2026-09-24T14:00:00Z',
};

/** The authorized detail — the only shape that reveals the exact address. */
export const reservationDetailFixture: ReservationDetail = {
  ...reservationFixture,
  address: '123 Wacker Dr',
  city: 'Chicago',
  state: 'IL',
  zipCode: '60601',
  spaceLabel: 'B17',
  parkingInstructions: 'Gate code 4821, park in the marked bay.',
  cancelledAt: null,
};
