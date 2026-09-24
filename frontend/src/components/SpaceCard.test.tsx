import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import SpaceCard from './SpaceCard';
import { publicSpaceFixture } from '../test-utils/fixtures';

vi.mock('../api/client', () => ({
  api: {
    photoUrl: (photo: { contentUrl: string }) => `http://test${photo.contentUrl}`,
  },
}));

function renderCard() {
  render(
    <MemoryRouter>
      <SpaceCard space={publicSpaceFixture} />
    </MemoryRouter>,
  );
}

describe('SpaceCard', () => {
  it('shows price, distance, and the approximate location', () => {
    renderCard();

    expect(screen.getByText('$3.00/hr')).toBeInTheDocument();
    expect(screen.getByText('0.2 mi away')).toBeInTheDocument();
    expect(screen.getByText('West Loop, Chicago, IL')).toBeInTheDocument();
  });

  it('shows availability, estimate, host first name + last initial, and features', () => {
    renderCard();

    expect(screen.getByText(/Hosted by Michael R\./)).toBeInTheDocument();
    expect(screen.getByText(/Est\. total \$6\.00/)).toBeInTheDocument();
    expect(screen.getByText(/Assigned space/)).toBeInTheDocument();
    expect(screen.getByText(/Sedan, SUV/)).toBeInTheDocument();
    expect(screen.getByText(/Covered/)).toBeInTheDocument();
  });

  it('never shows an exact address or space label — the DTO has no such fields', () => {
    renderCard();

    // The fixture's street address would be here if the type carried one.
    expect(screen.queryByText(/123 Wacker/)).not.toBeInTheDocument();
    expect(screen.queryByText(/B17/)).not.toBeInTheDocument();
    const keys = Object.keys(publicSpaceFixture);
    expect(keys).not.toContain('address');
    expect(keys).not.toContain('label');
    expect(keys).not.toContain('parkingInstructions');
    expect(keys).not.toContain('hostEmail');
  });

  it('shows "Free" for free shares', () => {
    render(
      <MemoryRouter>
        <SpaceCard space={{ ...publicSpaceFixture, hourlyRateCents: null }} />
      </MemoryRouter>,
    );

    expect(screen.getByText('Free')).toBeInTheDocument();
  });
});
