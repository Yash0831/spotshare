import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ActiveParkingBanner } from './ActiveParkingBanner';
import { reservationDetailFixture } from '../test-utils/fixtures';

const MIN = 60 * 1000;
const T0 = Date.parse('2026-09-24T12:00:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderBanner(minutesToDeparture: number) {
  const detail = {
    ...reservationDetailFixture,
    departure: new Date(T0 + minutesToDeparture * MIN).toISOString(),
  };
  render(<ActiveParkingBanner detail={detail} />);
}

describe('ActiveParkingBanner', () => {
  it('shows the live countdown with the leave-by time', () => {
    renderBanner(102);
    expect(screen.getByText("You're parked")).toBeInTheDocument();
    expect(screen.getByText('1 hr 42 min remaining')).toBeInTheDocument();
    expect(screen.getByText(/Leave by/)).toBeInTheDocument();
    // No 15-minute banner this far out.
    expect(
      screen.queryByText('15 minutes left — please head back to your car.'),
    ).not.toBeInTheDocument();
  });

  it('shows the persistent 15-minute reminder banner in the final stretch', () => {
    renderBanner(14);
    expect(screen.getByRole('alert')).toHaveTextContent(
      '15 minutes left — please head back to your car.',
    );
  });

  it('links to external directions with the exact address', () => {
    renderBanner(102);
    const link = screen.getByRole('link', { name: 'Get directions' });
    expect(link).toHaveAttribute(
      'href',
      expect.stringContaining('google.com/maps/dir/?api=1&destination='),
    );
    expect(link.getAttribute('href')).toContain('123%20Wacker%20Dr');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('warns when the time is up', () => {
    renderBanner(-1);
    expect(screen.getByText("Time's up")).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Your time is up — please move your car now.');
  });
});
