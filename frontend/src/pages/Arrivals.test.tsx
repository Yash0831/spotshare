import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import Arrivals from './Arrivals';
import { HostArrival } from '../api/types';

const HOUR = 60 * 60 * 1000;

const hostArrivalsAllMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { hostArrivalsAll: hostArrivalsAllMock },
  },
  tokenStore: { get access() { return 'access-1'; }, clear: vi.fn() },
}));

function arrivalFixture(overrides: Partial<HostArrival> = {}): HostArrival {
  const now = Date.now();
  return {
    id: 'arr-1',
    code: 'SP-K84D2',
    driverName: 'Dan D.',
    arrival: new Date(now + HOUR).toISOString(),
    departure: new Date(now + 2 * HOUR).toISOString(),
    status: 'CONFIRMED',
    cancelledBy: null,
    spaceId: 'space-1',
    spaceLabel: 'B17',
    ...overrides,
  };
}

function renderPage() {
  render(
    <MemoryRouter>
      <Arrivals />
    </MemoryRouter>,
  );
}

describe('Arrivals', () => {
  it('lists today\u2019s bookings across spaces with their space labels', async () => {
    hostArrivalsAllMock.mockResolvedValue([
      arrivalFixture(),
      arrivalFixture({
        id: 'arr-2',
        code: 'SP-Q91M8',
        driverName: 'Holly H.',
        spaceId: 'space-2',
        spaceLabel: 'C22',
      }),
    ]);
    renderPage();

    expect(await screen.findByText('B17')).toBeInTheDocument();
    expect(await screen.findByText('C22')).toBeInTheDocument();
    expect(screen.getByText('Dan D.')).toBeInTheDocument();
    expect(screen.getByText('Holly H.')).toBeInTheDocument();
    expect(screen.getByText('SP-K84D2')).toBeInTheDocument();
  });

  it('shows an empty state when there are no arrivals', async () => {
    hostArrivalsAllMock.mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/No arrivals today/)).toBeInTheDocument();
  });

  it('shows an error with a retry path when loading fails', async () => {
    hostArrivalsAllMock.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });
});
