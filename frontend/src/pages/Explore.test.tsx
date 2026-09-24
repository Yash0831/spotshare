import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import Explore from './Explore';
import { publicSpaceFixture } from '../test-utils/fixtures';

const searchMock = vi.hoisted(() => vi.fn());
const geocodeMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: {
    discovery: { search: searchMock, geocode: geocodeMock },
    photoUrl: (photo: { contentUrl: string }) => `http://test${photo.contentUrl}`,
  },
}));

// The real Leaflet map doesn't belong in a unit test; verify the wiring.
vi.mock('../components/MapView', () => ({
  default: ({ spaces }: { spaces: { id: string }[] }) => (
    <div data-testid="map">{spaces.length} pins</div>
  ),
}));

const candidate = { displayName: 'West Loop, Chicago, IL', latitude: 41.885, longitude: -87.619 };

function renderExplore() {
  render(
    <MemoryRouter>
      <Explore />
    </MemoryRouter>,
  );
}

describe('Explore', () => {
  beforeEach(() => {
    searchMock.mockReset();
    geocodeMock.mockReset();
  });

  it('requires a picked destination before searching', async () => {
    renderExplore();

    fireEvent.click(screen.getByRole('button', { name: 'Find parking' }));

    expect(await screen.findByText(/Pick a destination/)).toBeInTheDocument();
    expect(searchMock).not.toHaveBeenCalled();
  });

  it('searches with the picked location, times, and filters, then shows results', async () => {
    searchMock.mockResolvedValue({
      results: [publicSpaceFixture],
      page: 0,
      size: 50,
      hasMore: false,
    });
    geocodeMock.mockResolvedValue([candidate]);
    renderExplore();

    // Pick a destination from the suggestions.
    fireEvent.change(screen.getByLabelText('Destination'), { target: { value: 'West' } });
    await waitFor(() => expect(geocodeMock).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole('option', { name: /West Loop/ }));

    // Tighten the radius via filters.
    fireEvent.click(screen.getByRole('button', { name: /Filters/ }));
    fireEvent.change(screen.getByLabelText('Search radius'), { target: { value: '10' } });

    fireEvent.click(screen.getByRole('button', { name: 'Find parking' }));

    await waitFor(() => expect(searchMock).toHaveBeenCalled());
    const params = searchMock.mock.calls[0][0];
    expect(params.lat).toBe(41.885);
    expect(params.lng).toBe(-87.619);
    expect(params.radiusMiles).toBe(10);
    expect(new Date(params.arrival).getTime()).toBeLessThan(new Date(params.departure).getTime());

    expect(await screen.findByTestId('map')).toHaveTextContent('1 pins');
    expect(await screen.findByText('$3.00/hr')).toBeInTheDocument();
  });

  it('shows an empty state with recovery hints when nothing matches', async () => {
    searchMock.mockResolvedValue({ results: [], page: 0, size: 50, hasMore: false });
    geocodeMock.mockResolvedValue([candidate]);
    renderExplore();

    fireEvent.change(screen.getByLabelText('Destination'), { target: { value: 'West' } });
    await waitFor(() => expect(geocodeMock).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole('option', { name: /West Loop/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Find parking' }));

    expect(await screen.findByText('No spots found')).toBeInTheDocument();
    expect(screen.getByText(/Try a wider radius/)).toBeInTheDocument();
  });

  it('rejects a departure before the arrival', async () => {
    searchMock.mockReset();
    geocodeMock.mockResolvedValue([candidate]);
    renderExplore();

    fireEvent.change(screen.getByLabelText('Destination'), { target: { value: 'West' } });
    await waitFor(() => expect(geocodeMock).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole('option', { name: /West Loop/ }));

    // Departure before arrival: arrival defaults to now+1h, departure now+3h —
    // set departure earlier than arrival.
    fireEvent.change(screen.getByLabelText('Arriving'), { target: { value: '2026-09-25T12:00' } });
    fireEvent.change(screen.getByLabelText('Leaving'), { target: { value: '2026-09-25T10:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Find parking' }));

    expect(await screen.findByText(/departure has to be after/)).toBeInTheDocument();
    expect(searchMock).not.toHaveBeenCalled();
  });
});
