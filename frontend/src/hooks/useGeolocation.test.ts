import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useGeolocation } from './useGeolocation';

function mockGeolocation(impl: Partial<Geolocation>) {
  Object.defineProperty(navigator, 'geolocation', {
    value: impl,
    configurable: true,
    writable: true,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  // Remove the mock so 'geolocation' in navigator reflects the real env again.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  delete (navigator as any).geolocation;
});

describe('useGeolocation', () => {
  it('resolves coordinates on success', () => {
    mockGeolocation({
      getCurrentPosition: (_ok: PositionCallback) =>
        _ok({
          coords: { latitude: 41.88, longitude: -87.62 } as GeolocationCoordinates,
        } as GeolocationPosition),
    } as Geolocation);

    const { result } = renderHook(() => useGeolocation());
    act(() => {
      result.current.locate();
    });

    expect(result.current.status).toBe('ready');
    expect(result.current.position).toEqual({ lat: 41.88, lng: -87.62 });
  });

  it('reports denied when the user refuses permission', () => {
    mockGeolocation({
      getCurrentPosition: (_ok: PositionCallback, err?: PositionErrorCallback) =>
        err?.({
          code: 1,
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        } as GeolocationPositionError),
    } as Geolocation);

    const { result } = renderHook(() => useGeolocation());
    act(() => {
      result.current.locate();
    });

    expect(result.current.status).toBe('denied');
    expect(result.current.position).toBeNull();
  });

  it('reports unavailable when the browser has no geolocation', () => {
    const { result } = renderHook(() => useGeolocation());
    act(() => {
      result.current.locate();
    });

    expect(result.current.status).toBe('unavailable');
  });
});
