import {
  ApiError,
  ApiErrorBody,
  AuthResponse,
  LoginPayload,
  RegisterPayload,
  SessionExpiredError,
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

async function request<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
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
    return request<T>(path, init, true);
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
};
