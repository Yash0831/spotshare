import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import VacationMode from './VacationMode';
import { ApiError } from '../api/types';
import { formatTripLength } from '../utils/time';

const vacationMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    availability: {
      vacation: vacationMock,
    },
  },
}));

const defaultProps = {
  spaceId: 'space-1',
  onWindowsChanged: vi.fn(),
  onSessionExpired: vi.fn(),
};

/** A local datetime-local value `hours` hours from now. */
function hoursFromNow(hours: number): string {
  const d = new Date(Date.now() + hours * 3600 * 1000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

function openForm() {
  render(<VacationMode {...defaultProps} />);
  fireEvent.click(screen.getByRole('button', { name: /going on vacation/i }));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('formatTripLength', () => {
  it('formats multi-day trips as days and hours', () => {
    expect(formatTripLength(3 * 24 * 3600 * 1000 + 15 * 3600 * 1000)).toBe('3 days 15 hours');
  });

  it('uses singular forms', () => {
    expect(formatTripLength(25 * 3600 * 1000)).toBe('1 day 1 hour');
  });

  it('omits zero hours on an exact multi-day trip', () => {
    expect(formatTripLength(2 * 24 * 3600 * 1000)).toBe('2 days');
  });

  it('falls back to minutes for sub-hour trips', () => {
    expect(formatTripLength(45 * 60 * 1000)).toBe('45 min');
  });
});

describe('VacationMode', () => {
  it('opens the form from the vacation button', () => {
    openForm();
    expect(screen.getByRole('button', { name: /share for the trip/i })).toBeInTheDocument();
  });

  it('shows the trip length summary as the host picks dates', () => {
    openForm();
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(24 + 51) },
    });
    expect(screen.getByText(/shared for/i)).toHaveTextContent(/2 days 3 hours/);
  });

  it('rejects an end before the start with an inline error', () => {
    openForm();
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(48) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/must be after your start/i);
    expect(vacationMock).not.toHaveBeenCalled();
  });

  it('rejects a start in the past with an inline error', () => {
    openForm();
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(-24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/must be in the future/i);
    expect(vacationMock).not.toHaveBeenCalled();
  });

  it('rejects a trip shorter than 30 minutes', () => {
    openForm();
    const start = new Date(Date.now() + 3600 * 1000);
    const pad = (n: number) => String(n).padStart(2, '0');
    const fmt = (d: Date) =>
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: fmt(start) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: fmt(new Date(start.getTime() + 20 * 60 * 1000)) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/at least 30 minutes/i);
    expect(vacationMock).not.toHaveBeenCalled();
  });

  it('rejects an hourly price above $100', () => {
    openForm();
    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));
    fireEvent.change(screen.getByLabelText(/hourly price/i), {
      target: { value: '150' },
    });
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(48) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/can\u2019t be more than \$100/i);
    expect(vacationMock).not.toHaveBeenCalled();
  });

  it('creates a vacation window and refreshes the parent list', async () => {
    vacationMock.mockResolvedValue({ id: 'w-1' });
    openForm();
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(72) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));

    await waitFor(() => expect(vacationMock).toHaveBeenCalledTimes(1));
    const [, payload] = vacationMock.mock.calls[0] as [string, { hourlyRateCents: number | null }];
    expect(vacationMock.mock.calls[0][0]).toBe('space-1');
    expect(payload.hourlyRateCents).toBeNull();
    expect(defaultProps.onWindowsChanged).toHaveBeenCalledTimes(1);
  });

  it('sends the hourly price in integer cents', async () => {
    vacationMock.mockResolvedValue({ id: 'w-1' });
    openForm();
    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));
    fireEvent.change(screen.getByLabelText(/hourly price/i), {
      target: { value: '7.50' },
    });
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(48) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));

    await waitFor(() => expect(vacationMock).toHaveBeenCalledTimes(1));
    const [, payload] = vacationMock.mock.calls[0] as [string, { hourlyRateCents: number | null }];
    expect(payload.hourlyRateCents).toBe(750);
  });

  it('shows the server overlap error inline', async () => {
    vacationMock.mockRejectedValue(
      new ApiError(409, {
        code: 'OVERLAPPING_WINDOW',
        message: 'This space is already shared for part of that trip.',
        correlationId: 'cid',
      }),
    );
    openForm();
    fireEvent.change(screen.getByLabelText('Vacation start'), {
      target: { value: hoursFromNow(24) },
    });
    fireEvent.change(screen.getByLabelText('Vacation end'), {
      target: { value: hoursFromNow(48) },
    });
    fireEvent.click(screen.getByRole('button', { name: /share for the trip/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert')).toHaveTextContent(/already shared for part of that trip/i);
  });
});
