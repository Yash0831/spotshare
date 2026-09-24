import { useCallback, useRef, useState } from 'react';

export type GeoStatus = 'idle' | 'locating' | 'ready' | 'denied' | 'unavailable' | 'timeout';

export interface GeoPosition {
  lat: number;
  lng: number;
}

/**
 * Browser geolocation with honest states. `denied` means the user (or the
 * browser) refused — the caller shows a manual location entry instead.
 * `unavailable` means the device/browser has no geolocation at all.
 */
export function useGeolocation() {
  const [status, setStatus] = useState<GeoStatus>('idle');
  const [position, setPosition] = useState<GeoPosition | null>(null);
  const watchId = useRef<number | null>(null);

  const locate = useCallback(() => {
    if (!('geolocation' in navigator)) {
      setStatus('unavailable');
      return;
    }
    setStatus('locating');
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setPosition({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setStatus('ready');
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) setStatus('denied');
        else if (err.code === err.TIMEOUT) setStatus('timeout');
        else setStatus('unavailable');
      },
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 },
    );
  }, []);

  const reset = useCallback(() => {
    if (watchId.current != null) {
      navigator.geolocation.clearWatch(watchId.current);
      watchId.current = null;
    }
    setStatus('idle');
    setPosition(null);
  }, []);

  return { status, position, locate, reset };
}
