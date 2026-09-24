import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let api: typeof import('./client').api;
let tokenStore: typeof import('./client').tokenStore;
let ApiError: typeof import('./types').ApiError;
let SessionExpiredError: typeof import('./types').SessionExpiredError;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const authResponse = {
  accessToken: 'new-access',
  refreshToken: 'new-refresh',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 'u1',
    email: 'ada@example.com',
    firstName: 'Ada',
    lastName: 'Lovelace',
    phone: null,
    role: 'USER',
  },
};

beforeEach(async () => {
  vi.resetModules();
  localStorage.clear();
  const client = await import('./client');
  api = client.api;
  tokenStore = client.tokenStore;
  const types = await import('./types');
  ApiError = types.ApiError;
  SessionExpiredError = types.SessionExpiredError;
  vi.stubGlobal('fetch', vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('api client', () => {
  it('attaches the bearer token and returns typed data', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(json(200, authResponse.user));

    const user = await api.me();

    expect(user.email).toBe('ada@example.com');
    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect((init?.headers as Record<string, string>)['Authorization']).toBe('Bearer access-1');
  });

  it('refreshes once on 401 and retries the request', async () => {
    tokenStore.save('expired-access', 'refresh-1');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED', message: 'Please log in.' }))
      .mockResolvedValueOnce(json(200, authResponse))
      .mockResolvedValueOnce(json(200, authResponse.user));

    const user = await api.me();

    expect(user.email).toBe('ada@example.com');
    const calls = vi.mocked(fetch).mock.calls;
    expect(calls).toHaveLength(3);
    // The refresh call carried the refresh token in the body.
    expect(calls[1][0]).toContain('/auth/refresh');
    expect(calls[1][1]?.body).toContain('refresh-1');
    // Retried with the new access token.
    expect((calls[2][1]?.headers as Record<string, string>)['Authorization']).toBe(
      'Bearer new-access',
    );
    expect(tokenStore.access).toBe('new-access');
    expect(tokenStore.refresh).toBe('new-refresh');
  });

  it('throws SessionExpiredError and clears tokens when refresh fails', async () => {
    tokenStore.save('expired-access', 'bad-refresh');
    vi.mocked(fetch)
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED', message: 'Please log in.' }))
      .mockResolvedValueOnce(json(401, { code: 'INVALID_REFRESH_TOKEN', message: 'Expired.' }));

    await expect(api.me()).rejects.toBeInstanceOf(SessionExpiredError);
    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
  });

  it('surfaces the backend error on login instead of a session error', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      json(401, { code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect.' }),
    );

    const err = await api
      .login({ email: 'ada@example.com', password: 'wrong' })
      .catch((e) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('INVALID_CREDENTIALS');
    expect(err.message).toBe('Email or password is incorrect.');
  });

  it('logout clears local tokens and notifies the server', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));

    await api.logout();

    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toContain('/auth/logout');
    expect(init?.body).toContain('refresh-1');
  });

  it('spaces.mine fetches the owner space list', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(json(200, [{ id: 's1', label: 'B17' }]));

    const spaces = await api.spaces.mine();

    expect(spaces).toHaveLength(1);
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toContain('/spaces/mine');
    const authHeader = (init?.headers as Record<string, string>)['Authorization'];
    expect(authHeader?.startsWith('Bearer ')).toBe(true);
    expect(authHeader).toContain('access-1');
  });

  it('spaces.uploadPhoto sends FormData without a JSON content type', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(
      json(201, { id: 'p1', contentType: 'image/jpeg', sortOrder: 0 }),
    );
    const file = new File([new Uint8Array([1, 2, 3])], 'p.jpg', { type: 'image/jpeg' });

    await api.spaces.uploadPhoto('s1', file);

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toContain('/spaces/s1/photos');
    expect(init?.body).toBeInstanceOf(FormData);
    // The browser sets the multipart boundary; a manual JSON content type
    // would break the upload.
    expect((init?.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('photoUrl points at the public content endpoint', () => {
    expect(
      api.photoUrl({
        id: 'p1',
        contentType: 'image/jpeg',
        sortOrder: 0,
        contentUrl: '/api/v1/spaces/s1/photos/p1/content',
        createdAt: '2026-09-24T00:00:00Z',
      }),
    ).toContain('/api/v1/spaces/s1/photos/p1/content');
  });

  it('availability.share posts the return time and cents price', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(json(201, { id: 'w1' }));

    await api.availability.share('s1', {
      returnTime: '2026-09-24T14:00:00Z',
      hourlyRateCents: 350,
    });

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toContain('/spaces/s1/availability');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(init?.body as string)).toEqual({
      returnTime: '2026-09-24T14:00:00Z',
      hourlyRateCents: 350,
    });
  });

  it('availability.list and mine hit the right endpoints', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(json(200, [])).mockResolvedValueOnce(json(200, []));

    await api.availability.list('s1');
    await api.availability.mine();

    expect(vi.mocked(fetch).mock.calls[0][0]).toContain('/spaces/s1/availability');
    expect(vi.mocked(fetch).mock.calls[1][0]).toContain('/availability/mine');
  });

  it('availability.remove issues a DELETE', async () => {
    tokenStore.save('access-1', 'refresh-1');
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));

    await api.availability.remove('w1');

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toContain('/availability/w1');
    expect(init?.method).toBe('DELETE');
  });
});
