import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import ReservationDetail from './ReservationDetail';
import { ApiError } from '../api/types';
import { reservationDetailFixture } from '../test-utils/fixtures';

const getMock = vi.hoisted(() => vi.fn());
const cancelMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { get: getMock, cancel: cancelMock },
  },
  tokenStore: { get access() { return 'access-1'; }, clear: vi.fn() },
}));

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/reservations/res-1']}>
      <Routes>
        <Route path="/reservations/:id" element={<ReservationDetail />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ReservationDetail', () => {
  it('reveals the exact address and the booking code after confirming', async () => {
    getMock.mockReset();
    getMock.mockResolvedValue(reservationDetailFixture);
    renderDetail();

    // The address reveal — the whole point of booking.
    expect(await screen.findByText('123 Wacker Dr')).toBeInTheDocument();
    expect(screen.getByText('SP-K84D2')).toBeInTheDocument();
    expect(screen.getByText(/B17/)).toBeInTheDocument();
    expect(screen.getByText(/Gate code 4821/)).toBeInTheDocument();
    expect(screen.getByText('Cancel reservation')).toBeInTheDocument();
  });

  it('cancels with a confirmation step and shows the cancelled state', async () => {
    getMock.mockReset();
    cancelMock.mockReset();
    getMock.mockResolvedValue(reservationDetailFixture);
    cancelMock.mockResolvedValue({ ...reservationDetailFixture, status: 'CANCELLED' });
    renderDetail();

    fireEvent.click(await screen.findByText('Cancel reservation'));
    // Confirmation step appears first — no accidental taps.
    expect(screen.getByText('Cancel this reservation?')).toBeInTheDocument();
    expect(cancelMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('Yes, cancel'));
    expect(await screen.findByText('CANCELLED')).toBeInTheDocument();
    expect(cancelMock).toHaveBeenCalledWith('res-1');
    // The cancel button goes away once cancelled.
    expect(screen.queryByText('Cancel reservation')).not.toBeInTheDocument();
  });

  it('explains when the reservation belongs to someone else', async () => {
    getMock.mockReset();
    getMock.mockRejectedValue(new ApiError(403, { code: 'NOT_YOUR_RESERVATION', message: 'Forbidden' }));
    renderDetail();

    expect(await screen.findByText("Couldn't open that reservation")).toBeInTheDocument();
    expect(screen.getByText("This reservation isn't yours.")).toBeInTheDocument();
  });
});
