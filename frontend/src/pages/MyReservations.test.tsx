import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import MyReservations from './MyReservations';
import { Reservation } from '../api/types';
import { reservationFixture } from '../test-utils/fixtures';

const HOUR = 60 * 60 * 1000;

const mineMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { mine: mineMock },
  },
  tokenStore: { get access() { return 'access-1'; }, clear: vi.fn() },
}));

function upcomingReservation(): Reservation {
  const now = Date.now();
  return {
    ...reservationFixture,
    id: 'res-up',
    code: 'SP-UP111',
    arrival: new Date(now + 2 * HOUR).toISOString(),
    departure: new Date(now + 4 * HOUR).toISOString(),
  };
}

function pastReservation(): Reservation {
  const now = Date.now();
  return {
    ...reservationFixture,
    id: 'res-past',
    code: 'SP-PA222',
    status: 'COMPLETED',
    arrival: new Date(now - 6 * HOUR).toISOString(),
    departure: new Date(now - 4 * HOUR).toISOString(),
  };
}

function renderList() {
  return render(
    <MemoryRouter>
      <MyReservations />
    </MemoryRouter>,
  );
}

describe('MyReservations', () => {
  it('shows a loading state, then the empty state', async () => {
    mineMock.mockReset();
    let resolve!: (v: Reservation[]) => void;
    mineMock.mockReturnValue(new Promise<Reservation[]>((r) => { resolve = r; }));
    renderList();

    expect(screen.getByText('Loading reservations…')).toBeInTheDocument();

    resolve([]);
    expect(await screen.findByText('No reservations yet')).toBeInTheDocument();
    expect(screen.getByText('Find parking')).toBeInTheDocument();
  });

  it('splits upcoming and past reservations', async () => {
    mineMock.mockReset();
    mineMock.mockResolvedValue([upcomingReservation(), pastReservation()]);
    renderList();

    expect(await screen.findByText('SP-UP111')).toBeInTheDocument();
    expect(screen.getByText('SP-PA222')).toBeInTheDocument();
    expect(screen.getByText('Upcoming (1)')).toBeInTheDocument();
    expect(screen.getByText('Past (1)')).toBeInTheDocument();
    // The summary never reveals the exact address.
    expect(screen.queryByText('123 Wacker Dr')).not.toBeInTheDocument();
  });

  it('shows an error state when the list fails', async () => {
    mineMock.mockReset();
    mineMock.mockRejectedValue(new Error('network down'));
    renderList();

    expect(await screen.findByText("Couldn't load reservations")).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('network down');
  });
});
