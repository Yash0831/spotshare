import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ReturnEarlyDialog from './ReturnEarlyDialog';
import { ApiError, AvailabilityWindow } from '../api/types';

const returnEarlyMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    availability: {
      returnEarly: returnEarlyMock,
    },
  },
}));

const liveWindow: AvailabilityWindow = {
  id: 'w1',
  spaceId: 's1',
  startsAt: new Date(Date.now() - 3600 * 1000).toISOString(),
  endsAt: new Date(Date.now() + 2 * 3600 * 1000).toISOString(),
  source: 'MANUAL',
  hourlyRateCents: null,
  live: true,
  createdAt: new Date().toISOString(),
};

function renderDialog(window: AvailabilityWindow = liveWindow) {
  const onClose = vi.fn();
  const onUpdated = vi.fn();
  const onSessionExpired = vi.fn();
  render(
    <ReturnEarlyDialog
      window={window}
      spaceLabel="B17"
      onClose={onClose}
      onUpdated={onUpdated}
      onSessionExpired={onSessionExpired}
    />,
  );
  return { onClose, onUpdated, onSessionExpired };
}

describe('ReturnEarlyDialog', () => {
  beforeEach(() => {
    returnEarlyMock.mockClear();
    returnEarlyMock.mockResolvedValue({ ...liveWindow, live: false });
  });

  it('shows the current return time and the new-return preview', () => {
    renderDialog();

    expect(screen.getByRole('dialog', { name: 'Return early' })).toBeInTheDocument();
    expect(screen.getByText(/currently shared until/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Now' })).toHaveAttribute('aria-pressed', 'true');
    // "sooner than planned" preview for the Now default
    expect(screen.getByText(/sooner than planned/)).toBeInTheDocument();
  });

  it('quick chips switch the preview and the payload time', async () => {
    const { onUpdated } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '+30 min' }));
    fireEvent.click(screen.getByRole('button', { name: 'End share early' }));

    await waitFor(() => expect(returnEarlyMock).toHaveBeenCalledTimes(1));
    const [windowId, payload] = returnEarlyMock.mock.calls[0];
    expect(windowId).toBe('w1');
    const sent = new Date(payload.newReturnTime).getTime();
    // ~30 minutes out (allow a minute of test slop)
    expect(sent).toBeGreaterThan(Date.now() + 28 * 60 * 1000);
    expect(sent).toBeLessThan(Date.now() + 32 * 60 * 1000);
    expect(onUpdated).toHaveBeenCalledTimes(1);
  });

  it('custom time is sent through and validated client-side', async () => {
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: 'Custom' }));
    // A time after the current window end is not "early".
    const afterEnd = new Date(new Date(liveWindow.endsAt).getTime() + 3600 * 1000);
    const pad = (n: number) => String(n).padStart(2, '0');
    const local = `${afterEnd.getFullYear()}-${pad(afterEnd.getMonth() + 1)}-${pad(afterEnd.getDate())}T${pad(afterEnd.getHours())}:${pad(afterEnd.getMinutes())}`;
    fireEvent.change(screen.getByLabelText('Custom new return time'), {
      target: { value: local },
    });
    fireEvent.click(screen.getByRole('button', { name: 'End share early' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /not earlier than your current return time/,
    );
    expect(returnEarlyMock).not.toHaveBeenCalled();
  });

  it('shows the friendly blocked message with the earliest available return', async () => {
    const earliest = new Date(Date.now() + 90 * 60 * 1000).toISOString();
    returnEarlyMock.mockRejectedValueOnce(
      new ApiError(422, {
        code: 'RETURN_BLOCKED_BY_RESERVATION',
        message: 'Your space is reserved until 7:30 PM. Earliest available return: 7:30 PM.',
        details: { earliestReturnTime: earliest },
      }),
    );
    const { onUpdated } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: 'End share early' }));

    const alert = await screen.findByRole('alert');
    // Server message verbatim…
    expect(alert).toHaveTextContent('Your space is reserved until 7:30 PM.');
    // …plus the machine-readable earliest return, formatted for the viewer.
    expect(alert).toHaveTextContent('(Your time:');
    expect(onUpdated).not.toHaveBeenCalled();
    // The dialog stays open so the host can pick a later time.
    expect(screen.getByRole('dialog', { name: 'Return early' })).toBeInTheDocument();
  });

  it('shows other server errors as the friendly message', async () => {
    returnEarlyMock.mockRejectedValueOnce(
      new ApiError(422, {
        code: 'WINDOW_TOO_SHORT',
        message: 'Shares need to be at least 30 minutes long.',
      }),
    );
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '+15 min' }));
    fireEvent.click(screen.getByRole('button', { name: 'End share early' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Shares need to be at least 30 minutes long.',
    );
  });
});
