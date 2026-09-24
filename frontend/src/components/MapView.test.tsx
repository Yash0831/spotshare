import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import MapView from './MapView';
import { publicSpaceFixture } from '../test-utils/fixtures';

describe('MapView', () => {
  it('renders a price pin per result and notifies on pin tap', async () => {
    const onSelect = vi.fn();
    render(
      <MapView
        spaces={[
          publicSpaceFixture,
          { ...publicSpaceFixture, id: 'space-2', hourlyRateCents: null },
        ]}
        center={[41.885, -87.619]}
        onSelect={onSelect}
      />,
    );

    // The Leaflet map shell renders…
    const container = document.querySelector('.leaflet-container');
    expect(container).toBeInTheDocument();

    // …with one price pin per result.
    const pins = container!.querySelectorAll('.price-pin');
    expect(pins).toHaveLength(2);
    expect(pins[0].textContent).toBe('$3.00/hr');
    expect(pins[1].textContent).toBe('Free');

    // Tapping a pin selects that space.
    (pins[0] as HTMLElement).click();
    expect(onSelect).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'space-1' }),
    );

    expect(screen.getByText(/OpenStreetMap/)).toBeInTheDocument();
  });

  it('renders an empty map without results', () => {
    render(<MapView spaces={[]} />);

    const container = document.querySelector('.leaflet-container');
    expect(container).toBeInTheDocument();
    expect(container!.querySelectorAll('.price-pin')).toHaveLength(0);
  });
});
