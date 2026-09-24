import {
  ApiError,
  ApiErrorBody,
  AuthResponse,
  AvailabilityWindow,
  CreateReservationPayload,
  CreateSpacePayload,
  GeocodeCandidate,
  LoginPayload,
  ParkingSpace,
  RegisterPayload,
  Reservation,
  ReservationDetail,
  SearchParams,
  SearchResponse,
  SessionExpiredError,
  SharePayload,
  SpacePhoto,
  UpdateSpacePayload,
  User,
} from './types';

/**
 * Typed API client for the SpotShare backend.
 *
 * - Base URL from VITE_API_BASE_URL (default `/api/v1`, which Vite proxies to
 *   the backend in dev).
 * - Attaches the Bearer access token to every request.
 * - On a 401 it attempts ONE silent refresh-token rotation and retries the
 *   request; if refresh fails the session is cleared and a
 *   SessionExpiredError is thrown so the UI can send the user to /login.
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

const ACCESS_KEY = 'spotshare.accessToken';
const REFRESH_KEY = 'spotshare.refreshToken';

export const tokenStore = {
  get access(): string | null {
    return localStorage.getItem(ACCESS_KEY);
  },
  get refresh(): string | null {
    return localStorage.getItem(REFRESH_KEY);
  },
  save(access: string, refresh: string) {
    localStorage.setItem(ACCESS_KEY, access);
    localStorage.setItem(REFRESH_KEY, refresh);
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

async function parseError(res: Response): Promise<ApiError> {
  let body: ApiErrorBody = {
    code: 'UNKNOWN_ERROR',
    message: 'Something went wrong. Please try again.',
  };
  try {
    const json = (await res.json()) as Partial<ApiErrorBody>;
    if (json.code && json.message) body = json as ApiErrorBody;
  } catch {
    // non-JSON error page — keep the generic message
  }
  return new ApiError(res.status, body);
}

let refreshInFlight: Promise<AuthResponse> | null = null;

/** Single-flight refresh so concurrent 401s don't burn the refresh token. */
async function refreshTokens(): Promise<AuthResponse> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const refresh = tokenStore.refresh;
      if (!refresh) throw new SessionExpiredError();
      const res = await fetch(`${BASE_URL}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: refresh }),
      });
      if (!res.ok) {
        tokenStore.clear();
        throw new SessionExpiredError();
      }
      const data = (await res.json()) as AuthResponse;
      tokenStore.save(data.accessToken, data.refreshToken);
      return data;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  retried = false,
  multipart = false,
): Promise<T> {
  // Multipart uploads must NOT set Content-Type: the browser adds the
  // boundary itself. JSON requests get the default header.
  const headers: Record<string, string> = {
    ...(multipart ? {} : { 'Content-Type': 'application/json' }),
    ...((init.headers as Record<string, string> | undefined) ?? {}),
  };
  const access = tokenStore.access;
  if (access) headers['Authorization'] = `Bearer ${access}`;

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers });

  // A 401 only means "session expired" when we actually sent a token. A 401
  // from login/register (no token attached) carries the real error
  // (e.g. INVALID_CREDENTIALS) and must be shown as-is.
  if (res.status === 401 && access && !retried && tokenStore.refresh) {
    try {
      await refreshTokens();
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      tokenStore.clear();
      throw new SessionExpiredError();
    }
    return request<T>(path, init, true, multipart);
  }
  if (res.status === 401 && access) {
    tokenStore.clear();
    throw new SessionExpiredError();
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  register(payload: RegisterPayload): Promise<AuthResponse> {
    return request<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  login(payload: LoginPayload): Promise<AuthResponse> {
    return request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  logout(): Promise<void> {
    const refresh = tokenStore.refresh;
    tokenStore.clear();
    if (!refresh) return Promise.resolve();
    // Best effort: the server revokes the token; the client is logged out
    // regardless of the outcome.
    return request<void>('/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: refresh }),
    }).catch(() => undefined);
  },

  me(): Promise<User> {
    return request<User>('/me');
  },

  health(): Promise<{ status: string }> {
    return request<{ status: string }>('/health');
  },

  spaces: {
    /** The host's own spaces (owner DTO — includes private fields). */
    mine(): Promise<ParkingSpace[]> {
      return request<ParkingSpace[]>('/spaces/mine');
    },

    get(id: string): Promise<ParkingSpace> {
      return request<ParkingSpace>(`/spaces/${id}`);
    },

    create(payload: CreateSpacePayload): Promise<ParkingSpace> {
      return request<ParkingSpace>('/spaces', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },

    update(id: string, payload: UpdateSpacePayload): Promise<ParkingSpace> {
      return request<ParkingSpace>(`/spaces/${id}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
    },

    /** Soft delete: deactivates the space (active=false, record retained). */
    deactivate(id: string): Promise<ParkingSpace> {
      return request<ParkingSpace>(`/spaces/${id}`, { method: 'DELETE' });
    },

    uploadPhoto(id: string, file: File): Promise<SpacePhoto> {
      const form = new FormData();
      form.append('photo', file);
      return request<SpacePhoto>(`/spaces/${id}/photos`, { method: 'POST', body: form }, false, true);
    },

    deletePhoto(id: string, photoId: string): Promise<void> {
      return request<void>(`/spaces/${id}/photos/${photoId}`, { method: 'DELETE' });
    },
  },

  /** Absolute URL for a photo's bytes (the content endpoint is public). */
  photoUrl(photo: SpacePhoto): string {
    return `${BASE_URL}${photo.contentUrl}`;
  },

  availability: {
    /**
     * The one-tap "I'm leaving" share. The window starts now; the payload
     * carries the return time (ISO instant) and the optional hourly price in
     * cents (null = free).
     */
    share(spaceId: string, payload: SharePayload): Promise<AvailabilityWindow> {
      return request<AvailabilityWindow>(`/spaces/${spaceId}/availability`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
    },

    /** Upcoming + currently-live shares for one of the host's spaces. */
    list(spaceId: string): Promise<AvailabilityWindow[]> {
      return request<AvailabilityWindow[]>(`/spaces/${spaceId}/availability`);
    },

    /** Upcoming + currently-live shares across all of the host's spaces. */
    mine(): Promise<AvailabilityWindow[]> {
      return request<AvailabilityWindow[]>('/availability/mine');
    },

    /** Removes a share that hasn't started yet. Idempotent (204 on retry). */
    remove(windowId: string): Promise<void> {
      return request<void>(`/availability/${windowId}`, { method: 'DELETE' });
    },
  },

  discovery: {
    /**
     * Nearby spaces whose share window fully contains [arrival, departure),
     * nearest first. Public — no login required; the results are privacy-safe
     * by construction (approximate location, no exact address).
     */
    search(params: SearchParams): Promise<SearchResponse> {
      const q = new URLSearchParams();
      q.set('lat', String(params.lat));
      q.set('lng', String(params.lng));
      if (params.radiusMiles != null) q.set('radiusMiles', String(params.radiusMiles));
      q.set('arrival', params.arrival);
      q.set('departure', params.departure);
      if (params.maxPrice != null) q.set('maxPrice', params.maxPrice.toFixed(2));
      if (params.covered != null) q.set('covered', String(params.covered));
      if (params.evCharging != null) q.set('evCharging', String(params.evCharging));
      if (params.vehicleSize) q.set('vehicleSize', params.vehicleSize);
      if (params.page != null) q.set('page', String(params.page));
      if (params.size != null) q.set('size', String(params.size));
      return request<SearchResponse>(`/spaces/search?${q.toString()}`);
    },

    /** Address → coordinate candidates for the destination search box. */
    geocode(query: string): Promise<GeocodeCandidate[]> {
      return request<GeocodeCandidate[]>(
        `/geocode?${new URLSearchParams({ q: query }).toString()}`,
      );
    },
  },

  reservations: {
    /**
     * Books [arrival, departure) on a space. `idempotencyKey` must be
     * generated ONCE per booking attempt (e.g. when the Reserve screen
     * opens) and reused across retries, so a double-tap or a dropped
     * connection can never create two reservations: the server answers
     * 200 with the original booking on a replay.
     */
    create(
      payload: CreateReservationPayload,
      idempotencyKey: string,
    ): Promise<Reservation> {
      return request<Reservation>('/reservations', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(payload),
      });
    },

    /** Detail for the driver or the host — the only call that returns the exact address. */
    get(id: string): Promise<ReservationDetail> {
      return request<ReservationDetail>(`/reservations/${id}`);
    },

    /** The signed-in driver's reservations, newest first. */
    mine(): Promise<Reservation[]> {
      return request<Reservation[]>('/reservations/mine');
    },

    /**
     * Cancels an upcoming reservation. Idempotent: repeating the call for
     * an already-cancelled reservation still answers 200.
     */
    cancel(id: string): Promise<Reservation> {
      return request<Reservation>(`/reservations/${id}/cancel`, { method: 'POST' });
    },
  },
};
