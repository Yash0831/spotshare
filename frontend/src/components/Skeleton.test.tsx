import { render, screen } from '@testing-library/react';
import { ReservationSkeleton, SpaceCardSkeleton } from './Skeleton';

describe('Skeleton placeholders', () => {
  it('reservation skeleton is announced politely to screen readers', () => {
    render(<ReservationSkeleton />);
    const status = screen.getByRole('status', { name: 'Loading reservations' });
    expect(status).toBeInTheDocument();
  });

  it('space card skeleton is announced politely to screen readers', () => {
    render(<SpaceCardSkeleton />);
    const status = screen.getByRole('status', { name: 'Loading parking spots' });
    expect(status).toBeInTheDocument();
  });

  it('pulse blocks are hidden from assistive technology', () => {
    const { container } = render(<ReservationSkeleton />);
    const hidden = container.querySelectorAll('[aria-hidden="true"]');
    expect(hidden.length).toBeGreaterThan(0);
  });
});
