import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import ParkNow from './ParkNow';
import { publicSpaceFixture } from '../test-utils/fixtures';

const searchMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    discovery: { search: searchMock, geocode: vi.fn() },
    photoUrl: (photo: { contentUrl: string }) => `http://test${photo.contentUrl}`,
  },
}));

vi.mock('../components/MapView', () => ({
  default: ({ spaces }: { spaces: { id: string }[] }) => (
    <div data-testid="map">{spaces.length} pins</div>
  ),
}));

/** Geolocation that immediately succeeds at a fixed point. */
function mockGeoSuccess() {
  Object.defineProperty(navigator, 'geolocation', {
    value: {
      getCurrentPosition: (ok: PositionCallback) =>
        ok({
          coords: { latitude: 41.88, longitude: -87.62 } as GeolocationCoordinates,
        } as GeolocationPosition),
    },
    configurable: true,
    writable: true,
  });
}

/** Geolocation that reports the user refused permission. */
function mockGeoDenied() {
  Object.defineProperty(navigator, 'geolocation', {
    value: {
      getCurrentPosition: (_ok: PositionCallback, err?: PositionErrorCallback) =>
        err?.({
          code: 1,
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        } as GeolocationPositionError),
    },
    configurable: true,
    writable: true,
  });
}

function renderParkNow() {
  render(
    <MemoryRouter>
      <ParkNow />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  searchMock.mockReset();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  delete (navigator as any).geolocation;
});

describe('ParkNow', () => {
  it('searches now → +2h at the geolocated position', async () => {
    mockGeoSuccess();
    searchMock.mockResolvedValue({
      results: [publicSpaceFixture],
      page: 0,
      size: 50,
      hasMore: false,
    });
    renderParkNow();

    fireEvent.click(screen.getByRole('button', { name: /Find parking near me/ }));

    await waitFor(() => expect(searchMock).toHaveBeenCalled());
    const params = searchMock.mock.calls[0][0];
    expect(params.lat).toBe(41.88);
    expect(params.lng).toBe(-87.62);
    expect(params.radiusMiles).toBe(3);
    const spanHrs =
      (new Date(params.departure).getTime() - new Date(params.arrival).getTime()) / 3600000;
    expect(spanHrs).toBeCloseTo(2, 1);

    expect(await screen.findByTestId('map')).toHaveTextContent('1 pins');
    expect(await screen.findByText('$3.00/hr')).toBeInTheDocument();
  });

  it('offers manual destination entry when geolocation is denied', async () => {
    mockGeoDenied();
    renderParkNow();

    fireEvent.click(screen.getByRole('button', { name: /Find parking near me/ }));

    expect(await screen.findByText(/Location access was declined/)).toBeInTheDocument();
    expect(screen.getByLabelText('Destination')).toBeInTheDocument();
    expect(searchMock).not.toHaveBeenCalled();
  });

  it('shows an empty state when nothing is available right now', async () => {
    mockGeoSuccess();
    searchMock.mockResolvedValue({ results: [], page: 0, size: 50, hasMore: false });
    renderParkNow();

    fireEvent.click(screen.getByRole('button', { name: /Find parking near me/ }));

    expect(await screen.findByText('Nothing available right now')).toBeInTheDocument();
    expect(screen.getByText(/plan ahead from the/)).toBeInTheDocument();
  });
});
