import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import SearchFilters, { DEFAULT_FILTERS, DiscoveryFilters } from './SearchFilters';

function renderFilters(onChange = vi.fn()) {
  render(<SearchFilters filters={DEFAULT_FILTERS} onChange={onChange} />);
  return onChange;
}

describe('SearchFilters', () => {
  it('starts collapsed and expands on tap', () => {
    renderFilters();

    expect(screen.queryByLabelText('Search radius')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Filters/ }));
    expect(screen.getByLabelText('Search radius')).toBeInTheDocument();
  });

  it('reports radius changes', () => {
    const onChange = renderFilters();
    fireEvent.click(screen.getByRole('button', { name: /Filters/ }));

    fireEvent.change(screen.getByLabelText('Search radius'), { target: { value: '10' } });

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ radiusMiles: 10 } satisfies Partial<DiscoveryFilters>),
    );
  });

  it('reports max price, covered, EV, and vehicle size', () => {
    const onChange = renderFilters();
    fireEvent.click(screen.getByRole('button', { name: /Filters/ }));

    fireEvent.change(screen.getByLabelText(/Max price per hour/), { target: { value: '12.50' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ maxPrice: '12.50' } satisfies Partial<DiscoveryFilters>),
    );

    fireEvent.click(screen.getByLabelText('Covered parking'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ covered: true } satisfies Partial<DiscoveryFilters>),
    );

    fireEvent.click(screen.getByLabelText('EV charging available'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ evCharging: true } satisfies Partial<DiscoveryFilters>),
    );

    fireEvent.change(screen.getByLabelText('Vehicle size'), { target: { value: 'SUV' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ vehicleSize: 'SUV' } satisfies Partial<DiscoveryFilters>),
    );
  });
});
