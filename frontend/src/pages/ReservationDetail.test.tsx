import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import ReservationDetail from './ReservationDetail';
import { ApiError } from '../api/types';
import { reservationDetailFixture } from '../test-utils/fixtures';
import { formatDateTime } from '../utils/time';

const getMock = vi.hoisted(() => vi.fn());
const cancelMock = vi.hoisted(() => vi.fn());
const extendMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { get: getMock, cancel: cancelMock, extend: extendMock },
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

/**
 * The shared fixture pins its dates to 2026-09-24, so the reservation
 * reads as past once that day is over and the page hides the cancel
 * button. Shift it into the future: these tests are about an upcoming
 * reservation.
 */
function upcomingFixture() {
  const now = Date.now();
  const HOUR = 60 * 60 * 1000;
  return {
    ...reservationDetailFixture,
    arrival: new Date(now + 2 * HOUR).toISOString(),
    departure: new Date(now + 4 * HOUR).toISOString(),
  };
}

describe('ReservationDetail', () => {
  it('reveals the exact address and the booking code after confirming', async () => {
    getMock.mockReset();
    getMock.mockResolvedValue(upcomingFixture());
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
    const fixture = upcomingFixture();
    getMock.mockResolvedValue(fixture);
    cancelMock.mockResolvedValue({ ...fixture, status: 'CANCELLED' });
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

  it('extends the stay and shows the new departure time', async () => {
    getMock.mockReset();
    extendMock.mockReset();
    const fixture = upcomingFixture();
    getMock.mockResolvedValue(fixture);
    const newDeparture = new Date(new Date(fixture.departure).getTime() + 60 * 60_000).toISOString();
    extendMock.mockResolvedValue({ ...fixture, departure: newDeparture, totalCents: 900 });
    renderDetail();

    fireEvent.click(await screen.findByText('Extend stay'));
    // The picker opens with +1 hr selected by default.
    expect(screen.getByText('Extend your stay')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+1 hr', })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByText('Confirm extension'));
    expect(extendMock).toHaveBeenCalledWith('res-1', { newDeparture });
    // The panel closes and the detail updates in place: new departure
    // and the repriced total. (Assert after the close — the panel's own
    // "New departure" preview unmounts with it, so matching the text
    // while the panel is open races the unmount.)
    await waitFor(() => {
      expect(screen.queryByText('Extend your stay')).not.toBeInTheDocument();
    });
    expect(screen.getByText('Leaving').closest('div')).toHaveTextContent(
      formatDateTime(newDeparture),
    );
    expect(screen.getByText('$9.00')).toBeInTheDocument();
  });

  it('offers shorter extensions and shows the repriced total', async () => {
    getMock.mockReset();
    extendMock.mockReset();
    const fixture = upcomingFixture();
    getMock.mockResolvedValue(fixture);
    renderDetail();

    fireEvent.click(await screen.findByText('Extend stay'));
    fireEvent.click(screen.getByText('+2 hr'));
    // 4 hours at $3.00/hr.
    expect(screen.getByText('$12.00')).toBeInTheDocument();
  });

  it('shows a friendly message when the extra time was just reserved', async () => {
    getMock.mockReset();
    extendMock.mockReset();
    getMock.mockResolvedValue(upcomingFixture());
    extendMock.mockRejectedValue(
      new ApiError(409, { code: 'SPACE_JUST_RESERVED', message: 'That extra time was just reserved.' }),
    );
    renderDetail();

    fireEvent.click(await screen.findByText('Extend stay'));
    fireEvent.click(screen.getByText('Confirm extension'));

    expect(await screen.findByText(/Someone just booked that extra time/)).toBeInTheDocument();
    // The panel stays open so the driver can try a shorter extension.
    expect(screen.getByText('Extend your stay')).toBeInTheDocument();
  });

  it('hides the extend button once the reservation has ended', async () => {
    getMock.mockReset();
    const now = Date.now();
    const HOUR = 60 * 60 * 1000;
    getMock.mockResolvedValue({
      ...reservationDetailFixture,
      arrival: new Date(now - 3 * HOUR).toISOString(),
      departure: new Date(now - HOUR).toISOString(),
    });
    renderDetail();

    await screen.findByText('123 Wacker Dr');
    expect(screen.queryByText('Extend stay')).not.toBeInTheDocument();
  });

  it('shows the host phone as a tap-to-call link while confirmed', async () => {
    getMock.mockReset();
    getMock.mockResolvedValue({ ...upcomingFixture(), hostPhone: '+1-555-0100' });
    renderDetail();

    const link = await screen.findByRole('link', { name: '+1-555-0100' });
    expect(link).toHaveAttribute('href', 'tel:+1-555-0100');
    expect(screen.getByText('Host phone:')).toBeInTheDocument();
  });

  it('hides the phone rows when no number is shared', async () => {
    getMock.mockReset();
    getMock.mockResolvedValue(upcomingFixture());
    renderDetail();

    await screen.findByText('123 Wacker Dr');
    expect(screen.queryByText('Host phone:')).not.toBeInTheDocument();
    expect(screen.queryByText('Driver phone:')).not.toBeInTheDocument();
  });
});
