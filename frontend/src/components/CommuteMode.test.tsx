import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CommuteMode, { formatTimeOfDay } from './CommuteMode';
import { ApiError } from '../api/types';

const commuteMocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  remove: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
}));

vi.mock('../api/client', () => ({
  api: {
    commute: {
      list: commuteMocks.list,
      create: commuteMocks.create,
      remove: commuteMocks.remove,
      pause: commuteMocks.pause,
      resume: commuteMocks.resume,
    },
  },
}));

const schedule = (overrides = {}) => ({
  id: 'sched-1',
  spaceId: 'space-1',
  dayOfWeek: 0,
  startTime: '09:00',
  endTime: '17:00',
  hourlyRateCents: 300,
  timezone: 'America/Chicago',
  active: true,
  createdAt: '2026-09-24T12:00:00Z',
  ...overrides,
});

const defaultProps = {
  spaceId: 'space-1',
  onWindowsChanged: vi.fn(),
  onSessionExpired: vi.fn(),
};

describe('formatTimeOfDay', () => {
  it('formats 24h times as 12h', () => {
    expect(formatTimeOfDay('09:00')).toBe('9:00 AM');
    expect(formatTimeOfDay('17:30')).toBe('5:30 PM');
    expect(formatTimeOfDay('12:00')).toBe('12:00 PM');
    expect(formatTimeOfDay('00:15')).toBe('12:15 AM');
  });
});

describe('CommuteMode', () => {
  it('renders existing schedules with active badge', async () => {
    commuteMocks.list.mockResolvedValue([schedule()]);
    render(<CommuteMode {...defaultProps} />);

    await waitFor(() => expect(screen.getByText(/Monday/)).toBeInTheDocument());
    expect(screen.getByText(/Monday · 9:00 AM – 5:00 PM/)).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Pause')).toBeInTheDocument();
  });

  it('pauses an active schedule and refreshes windows', async () => {
    commuteMocks.list.mockResolvedValue([schedule()]);
    commuteMocks.pause.mockResolvedValue(schedule({ active: false }));
    render(<CommuteMode {...defaultProps} />);

    await waitFor(() => expect(screen.getByText('Pause')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Pause'));

    await waitFor(() => expect(commuteMocks.pause).toHaveBeenCalledWith('sched-1'));
    expect(screen.getByText('Paused')).toBeInTheDocument();
    expect(defaultProps.onWindowsChanged).toHaveBeenCalled();
  });

  it('resumes a paused schedule', async () => {
    commuteMocks.list.mockResolvedValue([schedule({ active: false })]);
    commuteMocks.resume.mockResolvedValue(schedule({ active: true }));
    render(<CommuteMode {...defaultProps} />);

    await waitFor(() => expect(screen.getByText('Resume')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Resume'));

    await waitFor(() => expect(commuteMocks.resume).toHaveBeenCalledWith('sched-1'));
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('deletes with a two-tap confirm', async () => {
    commuteMocks.list.mockResolvedValue([schedule()]);
    commuteMocks.remove.mockResolvedValue(undefined);
    render(<CommuteMode {...defaultProps} />);

    await waitFor(() => expect(screen.getByText('Delete')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Delete'));
    expect(screen.getByText('Tap again to delete')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Tap again to delete'));

    await waitFor(() => expect(commuteMocks.remove).toHaveBeenCalledWith('sched-1'));
    expect(defaultProps.onWindowsChanged).toHaveBeenCalled();
  });

  it('validates end-before-start before calling the API', async () => {
    commuteMocks.list.mockResolvedValue([]);
    render(<CommuteMode {...defaultProps} />);
    await waitFor(() => expect(screen.getByText(/Add a weekly pattern/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('+ Add a weekly pattern'));
    const times = screen.getAllByDisplayValue(/:/);
    fireEvent.change(times[0], { target: { value: '17:00' } });
    fireEvent.change(times[1], { target: { value: '09:00' } });
    fireEvent.click(screen.getByText(/Save/));

    expect(await screen.findByText('The end time must be after the start time.')).toBeInTheDocument();
    expect(commuteMocks.create).not.toHaveBeenCalled();
  });

  it('creates one entry per selected weekday and shows a preview', async () => {
    commuteMocks.list.mockResolvedValue([]);
    commuteMocks.create.mockImplementation(async (_spaceId: string, payload: { dayOfWeek: number }) =>
      schedule({ id: `sched-${payload.dayOfWeek}`, dayOfWeek: payload.dayOfWeek }),
    );
    render(<CommuteMode {...defaultProps} />);
    await waitFor(() => expect(screen.getByText(/Add a weekly pattern/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('+ Add a weekly pattern'));
    // Default selection is Mon–Fri; narrow to Monday only.
    for (const label of ['Tue', 'Wed', 'Thu', 'Fri']) {
      fireEvent.click(screen.getByRole('button', { name: label }));
    }
    expect(screen.getByText(/Next shares:/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(commuteMocks.create).toHaveBeenCalledTimes(1));
    expect(commuteMocks.create).toHaveBeenCalledWith(
      'space-1',
      expect.objectContaining({ dayOfWeek: 0, startTime: '09:00', endTime: '17:00', timezone: expect.any(String) }),
    );
    expect(defaultProps.onWindowsChanged).toHaveBeenCalled();
  });

  it('shows the server error when a weekday already has a schedule', async () => {
    commuteMocks.list.mockResolvedValue([]);
    commuteMocks.create.mockRejectedValue(
      new ApiError(409, { code: 'SCHEDULE_EXISTS', message: 'You already have a commute entry for Monday.' }),
    );
    render(<CommuteMode {...defaultProps} />);
    await waitFor(() => expect(screen.getByText(/Add a weekly pattern/)).toBeInTheDocument());

    fireEvent.click(screen.getByText('+ Add a weekly pattern'));
    for (const label of ['Tue', 'Wed', 'Thu', 'Fri']) {
      fireEvent.click(screen.getByRole('button', { name: label }));
    }
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('You already have a commute entry for Monday.')).toBeInTheDocument();
  });
});
