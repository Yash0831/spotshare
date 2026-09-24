/** Shared API shapes. Mirrors the backend DTOs; kept in sync by hand. */

export type Role = 'USER' | 'ADMIN';

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  phone: string | null;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: User;
}

export interface RegisterPayload {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  phone?: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

/** Backend error envelope: { code, message, correlationId, details? }. */
export interface ApiErrorBody {
  code: string;
  message: string;
  correlationId?: string;
  /** Machine-readable extras for a single error (e.g. earliestReturnTime). */
  details?: Record<string, unknown>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code;
    this.details = body.details;
  }
}

/** Thrown when the session can't be refreshed — the user must log in again. */
export class SessionExpiredError extends Error {
  constructor() {
    super('Your session has expired. Please log in again.');
    this.name = 'SessionExpiredError';
  }
}

/** Space display states — derived server-side, never stored. */
export type DisplayState = 'OFFLINE' | 'PRIVATE' | 'AVAILABLE' | 'RESERVED' | 'RETURNING';

export const DISPLAY_STATE_LABELS: Record<DisplayState, string> = {
  OFFLINE: 'OFFLINE',
  PRIVATE: 'PRIVATE',
  AVAILABLE: 'AVAILABLE',
  RESERVED: 'RESERVED',
  RETURNING: 'RETURNING',
};

/** A host's share window. `live` is derived server-side from the timestamps. */
export interface AvailabilityWindow {
  id: string;
  spaceId: string;
  startsAt: string;
  endsAt: string;
  source: 'MANUAL' | 'COMMUTE' | 'VACATION';
  /** Integer cents per hour; null = free share. */
  hourlyRateCents: number | null;
  live: boolean;
  createdAt: string;
}

/** The one-tap share payload. The window starts now; the host picks return + price. */
export interface SharePayload {
  /** ISO instant of the host's return time. */
  returnTime: string;
  /** Integer cents per hour; null/omitted = free. */
  hourlyRateCents?: number | null;
}

/** The vacation-mode payload: a multi-day share with a host-picked start and end. */
export interface VacationPayload {
  /** ISO instant of the vacation start. */
  startDateTime: string;
  /** ISO instant of the vacation end. */
  endDateTime: string;
  /** Integer cents per hour; null/omitted = free. */
  hourlyRateCents?: number | null;
}

/**
 * One weekly commute entry. dayOfWeek is 0 = Monday .. 6 = Sunday; startTime
 * and endTime are local "HH:MM" times-of-day in the schedule's timezone.
 */
export interface CommuteSchedule {
  id: string;
  spaceId: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  /** Integer cents per hour; null = free commute window. */
  hourlyRateCents: number | null;
  /** IANA zone the times were entered in, e.g. "America/Chicago". */
  timezone: string;
  /** False while paused: no new windows are materialized. */
  active: boolean;
  createdAt: string;
}

/** Create one weekly commute entry for a space. */
export interface CreateCommuteSchedulePayload {
  dayOfWeek: number;
  /** Local "HH:MM" time-of-day. */
  startTime: string;
  /** Local "HH:MM" time-of-day. */
  endTime: string;
  /** Integer cents per hour; null = free. */
  hourlyRateCents: number | null;
  /** IANA zone name, e.g. "America/Chicago". */
  timezone: string;
}

/** Space types — the exact V1 set from the product spec. */
export type ParkingType =
  | 'DRIVEWAY'
  | 'PRIVATE_GARAGE'
  | 'ASSIGNED_SPACE'
  | 'PRIVATE_LOT'
  | 'EV_SPACE'
  | 'OTHER_PRIVATE';

export const PARKING_TYPE_LABELS: Record<ParkingType, string> = {
  DRIVEWAY: 'Driveway',
  PRIVATE_GARAGE: 'Private garage',
  ASSIGNED_SPACE: 'Assigned space',
  PRIVATE_LOT: 'Private lot',
  EV_SPACE: 'EV charging space',
  OTHER_PRIVATE: 'Other private space',
};

/** Vehicle sizes a space can hold. */
export type VehicleSize = 'MOTORCYCLE' | 'SEDAN' | 'SUV' | 'VAN' | 'TRUCK';

export const VEHICLE_SIZE_LABELS: Record<VehicleSize, string> = {
  MOTORCYCLE: 'Motorcycle',
  SEDAN: 'Sedan',
  SUV: 'SUV',
  VAN: 'Van',
  TRUCK: 'Truck',
};

/** A photo of a space. `contentUrl` serves the bytes (public by design). */
export interface SpacePhoto {
  id: string;
  contentType: string;
  sortOrder: number;
  contentUrl: string;
  createdAt: string;
}

/**
 * The owner's view of a space. It includes the private fields (exact address,
 * space label, parking instructions) because the caller is the host.
 * Discovery (Phase 4) gets its own privacy-safe DTO; privacy is enforced in
 * the backend DTOs. {@code displayState} is the derived availability status.
 */
export interface ParkingSpace {
  id: string;
  hostId: string;
  label: string;
  address: string;
  city: string;
  state: string;
  zipCode: string;
  latitude: number;
  longitude: number;
  areaLabel: string;
  parkingType: ParkingType;
  description: string | null;
  vehicleSizes: VehicleSize[];
  heightLimitInches: number | null;
  covered: boolean;
  evCharging: boolean;
  parkingInstructions: string | null;
  authorizationConfirmed: boolean;
  authorizationConfirmedAt: string;
  active: boolean;
  photos: SpacePhoto[];
  /** Derived server-side from the active flag + share windows. */
  displayState: DisplayState;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSpacePayload {
  label: string;
  address: string;
  city: string;
  state: string;
  zipCode: string;
  latitude: number;
  longitude: number;
  areaLabel: string;
  parkingType: ParkingType;
  description?: string;
  vehicleSizes: VehicleSize[];
  heightLimitInches?: number;
  covered: boolean;
  evCharging: boolean;
  parkingInstructions?: string;
  authorizationConfirmed: boolean;
}

export type UpdateSpacePayload = Omit<CreateSpacePayload, 'authorizationConfirmed'>;

/**
 * A privacy-safe discovery result. By construction this can never carry an
 * exact address, a space label/number, parking instructions, or host contact
 * details — the backend DTO has no such fields (spec §11). Location is
 * approximate (3-decimal coordinates + area label); the host is "first name
 * + last initial".
 */
export interface PublicSpace {
  id: string;
  areaLabel: string;
  city: string;
  state: string;
  parkingType: ParkingType;
  description: string | null;
  vehicleSizes: VehicleSize[];
  heightLimitInches: number | null;
  covered: boolean;
  evCharging: boolean;
  /** Approximate latitude, rounded to 3 decimals (~110 m). */
  approxLatitude: number;
  /** Approximate longitude, rounded to 3 decimals (~110 m). */
  approxLongitude: number;
  /** Distance from the search point in miles, one decimal. */
  distanceMiles: number;
  /** "Michael R." — first name plus last initial. */
  hostName: string;
  /** Integer cents per hour; null = free share. */
  hourlyRateCents: number | null;
  /** Prorated total for the searched period, in cents. */
  estimatedTotalCents: number;
  windowStartsAt: string;
  windowEndsAt: string;
  photos: SpacePhoto[];
}

export interface SearchParams {
  lat: number;
  lng: number;
  /** Search radius in miles (0.5–25); omitted → 3. */
  radiusMiles?: number;
  /** ISO instants; the share window must contain [arrival, departure). */
  arrival: string;
  departure: string;
  /** Maximum hourly rate in dollars, e.g. 12.5; free spaces always match. */
  maxPrice?: number;
  covered?: boolean;
  evCharging?: boolean;
  vehicleSize?: VehicleSize;
  page?: number;
  size?: number;
}

export interface SearchResponse {
  results: PublicSpace[];
  page: number;
  size: number;
  hasMore: boolean;
}

/** One address-lookup candidate: a place name and its coordinates. */
export interface GeocodeCandidate {
  displayName: string;
  latitude: number;
  longitude: number;
}

/** Reservation lifecycle. Terminal states: CANCELLED, COMPLETED. */
export type ReservationStatus = 'CONFIRMED' | 'CANCELLED' | 'COMPLETED';

/**
 * A driver's reservation, privacy-safe by construction: no exact address,
 * no space label, no parking instructions — those only ever leave the
 * server on the authorized detail view below.
 */
export interface Reservation {
  id: string;
  /** Short confirmation code, e.g. "SP-K84D2" — quote it when contacting the host. */
  code: string;
  status: ReservationStatus;
  arrival: string;
  departure: string;
  /** Integer cents per hour; null = free share. */
  hourlyRateCents: number | null;
  /** Integer cents actually owed, prorated to the minute and rounded HALF_UP. */
  totalCents: number;
  spaceId: string;
  spaceTypeLabel: string;
  hostName: string;
  /** Who cancelled, if anyone — lets the UI say who honestly. */
  cancelledBy: 'DRIVER' | 'HOST' | null;
  createdAt: string;
}

/**
 * The reservation detail, visible only to the driver or the host. This is
 * the ONLY response in the API that carries the exact address, the space
 * label, and the parking instructions — they are revealed by the act of
 * booking, never in discovery or in reservation summaries.
 */
export interface ReservationDetail extends Reservation {
  address: string;
  city: string;
  state: string;
  zipCode: string;
  spaceLabel: string;
  parkingInstructions: string | null;
  cancelledAt: string | null;
}

export interface CreateReservationPayload {
  spaceId: string;
  /** ISO instants; the period must sit inside one live share window. */
  arrival: string;
  departure: string;
}

/**
 * One row of the host's arrivals view (GET /spaces/:id/reservations).
 * Privacy-safe by design: the driver is first name + last initial only —
 * no contact details — and there is no exact address (the host already
 * knows their own).
 */
export interface HostArrival {
  id: string;
  code: string;
  driverName: string;
  arrival: string;
  departure: string;
  status: ReservationStatus;
  cancelledBy: 'DRIVER' | 'HOST' | null;
}
