import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DestinationInput from './DestinationInput';

const geocodeMock = vi.hoisted(() => vi.fn());

vi.mock('../api/client', () => ({
  api: { discovery: { geocode: geocodeMock } },
}));

const candidate = { displayName: 'West Loop, Chicago, IL', latitude: 41.885, longitude: -87.619 };

beforeEach(() => {
  vi.useFakeTimers();
  geocodeMock.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

/** Fire the 350ms debounce and flush the mocked lookup's promises. */
async function flushLookup() {
  act(() => {
    vi.advanceTimersByTime(500);
  });
  await act(async () => {});
}

function typeDestination(text: string) {
  fireEvent.change(screen.getByLabelText('Destination'), { target: { value: text } });
}

describe('DestinationInput', () => {
  it('looks up suggestions after typing 3+ characters (debounced)', async () => {
    geocodeMock.mockResolvedValue([candidate]);
    const onPick = vi.fn();
    render(<DestinationInput value="" onPick={onPick} />);

    typeDestination('We');
    await flushLookup();
    expect(geocodeMock).not.toHaveBeenCalled();

    typeDestination('West');
    await flushLookup();
    expect(geocodeMock).toHaveBeenCalledWith('West');
    expect(screen.getByRole('option', { name: /West Loop/ })).toBeInTheDocument();
  });

  it('picking a suggestion reports its coordinates and label', async () => {
    geocodeMock.mockResolvedValue([candidate]);
    const onPick = vi.fn();
    render(<DestinationInput value="" onPick={onPick} />);

    typeDestination('West Loop');
    await flushLookup();
    fireEvent.click(screen.getByRole('option', { name: /West Loop/ }));

    expect(onPick).toHaveBeenCalledWith({
      lat: 41.885,
      lng: -87.619,
      label: 'West Loop, Chicago, IL',
    });
    expect(screen.getByLabelText('Destination')).toHaveValue('West Loop, Chicago, IL');
  });

  it('a lookup failure keeps the typed text and notes the outage', async () => {
    geocodeMock.mockRejectedValue(new Error('boom'));
    const onPick = vi.fn();
    render(<DestinationInput value="" onPick={onPick} />);

    typeDestination('West Loop');
    await flushLookup();

    expect(screen.getByText(/Place search is unavailable/)).toBeInTheDocument();
    expect(screen.getByLabelText('Destination')).toHaveValue('West Loop');
    expect(onPick).not.toHaveBeenCalled();
  });
});
