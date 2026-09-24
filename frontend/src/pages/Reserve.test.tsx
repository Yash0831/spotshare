import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import Reserve from './Reserve';
import { ApiError } from '../api/types';
import { publicSpaceFixture } from '../test-utils/fixtures';

const HOUR = 60 * 60 * 1000;

const createMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    reservations: { create: createMock },
  },
  tokenStore: { get access() { return 'access-1'; }, clear: vi.fn() },
}));

/** A share window of now+1h → now+5h and a 2 h trip inside it ($3.00/hr → $6.00). */
function dynamicState() {
  const now = Date.now();
  return {
    space: {
      ...publicSpaceFixture,
      windowStartsAt: new Date(now + HOUR).toISOString(),
      windowEndsAt: new Date(now + 5 * HOUR).toISOString(),
    },
    arrival: new Date(now + 2 * HOUR).toISOString(),
    departure: new Date(now + 4 * HOUR).toISOString(),
  };
}

function renderReserve(state: unknown = dynamicState(), path = '/spaces/space-1/reserve') {
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      <Routes>
        <Route path="/spaces/:id/reserve" element={<Reserve />} />
        <Route path="/reservations/:id" element={<p>confirmation page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

function arrivalInput(): HTMLInputElement {
  return screen.getByLabelText('Arriving') as HTMLInputElement;
}

function departureInput(): HTMLInputElement {
  return screen.getByLabelText('Leaving') as HTMLInputElement;
}

/** "2026-09-24T14:30" from a Date, matching datetime-local. */
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

describe('Reserve', () => {
  it('shows a live prorated estimate for the searched times', () => {
    renderReserve();
    // 2 h at $3.00/hr
    expect(screen.getByText('$6.00')).toBeInTheDocument();
    expect(screen.getByText('Reserve this spot')).toBeInTheDocument();
  });

  it('updates the estimate when the times change', () => {
    renderReserve();
    // Shrink to one hour → $3.00.
    const oneHourLater = new Date(new Date(arrivalInput().value).getTime() + HOUR);
    fireEvent.change(departureInput(), { target: { value: toLocalInput(oneHourLater) } });
    expect(screen.getByText('$3.00')).toBeInTheDocument();
  });

  it('blocks a departure that is not after arrival', () => {
    renderReserve();
    fireEvent.change(departureInput(), { target: { value: arrivalInput().value } });
    expect(screen.getByRole('alert')).toHaveTextContent('departure has to be after your arrival');
    expect(screen.getByText('Confirm reservation')).toBeDisabled();
    expect(createMock).not.toHaveBeenCalled();
  });

  it('submits once with an idempotency key and opens the confirmation', async () => {
    const state = dynamicState();
    createMock.mockReset();
    createMock.mockResolvedValue({ id: 'res-1', code: 'SP-K84D2' });
    renderReserve(state);

    const button = screen.getByText('Confirm reservation');
    fireEvent.click(button);
    fireEvent.click(button); // double-tap — must not double-book

    await waitFor(() => expect(screen.getByText('confirmation page')).toBeInTheDocument());
    expect(createMock).toHaveBeenCalledTimes(1);
    const [payload, key] = createMock.mock.calls[0];
    expect(payload.spaceId).toBe('space-1');
    // The datetime-local inputs are minute-precision, so seconds are
    // truncated on the round trip through the form.
    const minuteFloored = (iso: string) => Math.floor(new Date(iso).getTime() / 60000) * 60000;
    expect(new Date(payload.arrival).getTime()).toBe(minuteFloored(state.arrival));
    expect(new Date(payload.departure).getTime()).toBe(minuteFloored(state.departure));
    expect(typeof key).toBe('string');
    expect(key.length).toBeGreaterThan(0);
  });

  it('shows a friendly message when someone else books first', async () => {
    createMock.mockReset();
    createMock.mockRejectedValue(
      new ApiError(409, { code: 'SPACE_JUST_RESERVED', message: 'Overlapping confirmed reservation' }),
    );
    renderReserve();

    fireEvent.click(screen.getByText('Confirm reservation'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Someone just booked this spot for those times',
    );
    // The user can try again — the button is re-enabled.
    expect(screen.getByText('Confirm reservation')).not.toBeDisabled();
  });

  it('explains itself when opened without a search result', () => {
    renderReserve(null);
    expect(screen.getByText('This booking needs a fresh search')).toBeInTheDocument();
  });
});
