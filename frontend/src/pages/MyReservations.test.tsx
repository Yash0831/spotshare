import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import MyReservations from './MyReservations';
import { Reservation } from '../api/types';
import { reservationFixture } from '../test-utils/fixtures';

const HOUR = 60 * 60 * 1000;

const mineMock = vi.hoisted(() => vi.fn());
const cancelMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { mine: mineMock, cancel: cancelMock },
  },
  tokenStore: { get access() { return 'access-1'; }, clear: vi.fn() },
}));

function lists(active: Reservation[], upcoming: Reservation[], past: Reservation[]) {
  mineMock.mockImplementation((filter?: string) => {
    if (filter === 'active') return Promise.resolve(active);
    if (filter === 'past') return Promise.resolve(past);
    return Promise.resolve(upcoming);
  });
}

function activeReservation(): Reservation {
  const now = Date.now();
  return {
    ...reservationFixture,
    id: 'res-act',
    code: 'SP-AC111',
    arrival: new Date(now - HOUR).toISOString(),
    departure: new Date(now + HOUR).toISOString(),
  };
}

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

  it('uses the server filters: active, upcoming, past', async () => {
    mineMock.mockReset();
    lists([activeReservation()], [upcomingReservation()], [pastReservation()]);
    renderList();

    expect(await screen.findByText('Active (1)')).toBeInTheDocument();
    expect(screen.getByText('Upcoming (1)')).toBeInTheDocument();
    expect(screen.getByText('Past (1)')).toBeInTheDocument();
    expect(mineMock).toHaveBeenCalledWith('active');
    expect(mineMock).toHaveBeenCalledWith('upcoming');
    expect(mineMock).toHaveBeenCalledWith('past');
    // The active row shows a live countdown; summaries never reveal the address.
    expect(screen.getByText(/left$/)).toBeInTheDocument();
    expect(screen.queryByText('123 Wacker Dr')).not.toBeInTheDocument();
  });

  it('labels a host-cancelled reservation honestly', async () => {
    mineMock.mockReset();
    const cancelled = { ...pastReservation(), status: 'CANCELLED' as const, cancelledBy: 'HOST' as const };
    lists([], [], [cancelled]);
    renderList();

    expect(await screen.findByText('Cancelled by host')).toBeInTheDocument();
  });

  it('cancels an upcoming reservation with a confirm step', async () => {
    const user = userEvent.setup();
    mineMock.mockReset();
    cancelMock.mockReset();
    lists([], [upcomingReservation()], []);
    cancelMock.mockResolvedValue({ ...upcomingReservation(), status: 'CANCELLED' });
    renderList();

    await screen.findByText('SP-UP111');
    await user.click(screen.getByRole('button', { name: 'Cancel reservation' }));
    // Confirm step appears; nothing cancelled yet.
    expect(cancelMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Yes, cancel' }));
    expect(cancelMock).toHaveBeenCalledWith('res-up');
    // The lists refresh after cancellation.
    expect(await screen.findByText('Upcoming (1)')).toBeInTheDocument();
  });

  it('shows an error state when the list fails', async () => {
    mineMock.mockReset();
    mineMock.mockRejectedValue(new Error('network down'));
    renderList();

    expect(await screen.findByText("Couldn't load reservations")).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('network down');
  });
});
