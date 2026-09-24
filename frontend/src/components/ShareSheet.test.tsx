import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ShareSheet from './ShareSheet';
import { AvailabilityWindow } from '../api/types';

const shareMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    availability: {
      share: shareMock,
    },
  },
}));

const sharedWindow: AvailabilityWindow = {
  id: 'w1',
  spaceId: 's1',
  startsAt: new Date().toISOString(),
  endsAt: new Date(Date.now() + 2 * 3600 * 1000).toISOString(),
  source: 'MANUAL',
  hourlyRateCents: null,
  live: true,
  createdAt: new Date().toISOString(),
};

function renderSheet() {
  const onClose = vi.fn();
  const onShared = vi.fn();
  const onSessionExpired = vi.fn();
  render(
    <ShareSheet
      spaceId="s1"
      spaceLabel="B17"
      onClose={onClose}
      onShared={onShared}
      onSessionExpired={onSessionExpired}
    />,
  );
  return { onClose, onShared, onSessionExpired };
}

describe('ShareSheet', () => {
  beforeEach(() => {
    shareMock.mockClear();
  });

  it('renders with 2h and free selected by default', () => {
    renderSheet();

    expect(screen.getByRole('button', { name: '2h' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Free' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByLabelText('Hourly price in dollars')).not.toBeInTheDocument();
  });

  it('switching to hourly reveals the price input', () => {
    renderSheet();

    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));

    expect(screen.getByLabelText('Hourly price in dollars')).toBeInTheDocument();
  });

  it('blocks an invalid hourly price without calling the API', () => {
    renderSheet();

    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));
    fireEvent.change(screen.getByLabelText('Hourly price in dollars'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Share my spot' }));

    expect(
      screen.getByText('Enter an hourly price above $0, or choose Free.'),
    ).toBeInTheDocument();
    expect(shareMock).not.toHaveBeenCalled();
  });

  it('blocks a price over the $100 cap', () => {
    renderSheet();

    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));
    fireEvent.change(screen.getByLabelText('Hourly price in dollars'), {
      target: { value: '150' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Share my spot' }));

    expect(screen.getByText(/can\u2019t be more than \$100/)).toBeInTheDocument();
    expect(shareMock).not.toHaveBeenCalled();
  });

  it('shares free with the 2h return time on confirm', async () => {
    const { onShared } = renderSheet();
    shareMock.mockResolvedValue(sharedWindow);

    fireEvent.click(screen.getByRole('button', { name: 'Share my spot' }));

    expect(shareMock).toHaveBeenCalledTimes(1);
    const [spaceId, payload] = shareMock.mock.calls[0] as [string, { returnTime: string; hourlyRateCents: null }];
    expect(spaceId).toBe('s1');
    expect(payload.hourlyRateCents).toBeNull();
    // Return time should be ~2h from now (allow a few minutes of test slop).
    const delta = new Date(payload.returnTime).getTime() - Date.now();
    expect(delta).toBeGreaterThan(110 * 60 * 1000);
    expect(delta).toBeLessThan(130 * 60 * 1000);

    await waitFor(() => expect(onShared).toHaveBeenCalledWith(sharedWindow));
  });

  it('shares with a paid hourly rate', async () => {
    renderSheet();
    shareMock.mockResolvedValue({ ...sharedWindow, hourlyRateCents: 350 });

    fireEvent.click(screen.getByRole('button', { name: 'Hourly' }));
    fireEvent.change(screen.getByLabelText('Hourly price in dollars'), {
      target: { value: '3.50' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Share my spot' }));

    expect(shareMock).toHaveBeenCalledTimes(1);
    const [, payload] = shareMock.mock.calls[0] as [string, { hourlyRateCents: number }];
    expect(payload.hourlyRateCents).toBe(350);
  });

  it('shows the backend error message when sharing fails', async () => {
    renderSheet();
    const { ApiError } = await import('../api/types');
    shareMock.mockRejectedValue(new ApiError(409, {
      code: 'OVERLAPPING_WINDOW',
      message: 'This space is already shared for part of that time.',
    }));

    fireEvent.click(screen.getByRole('button', { name: 'Share my spot' }));

    await screen.findByText('This space is already shared for part of that time.');
  });
});
