import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import MyParking from './MyParking';
import { ApiError, User } from '../api/types';

const spacesMocks = vi.hoisted(() => ({
  mine: vi.fn(),
  deactivate: vi.fn(),
}));

const availabilityMocks = vi.hoisted(() => ({
  mine: vi.fn(),
  remove: vi.fn(),
}));

vi.mock('../api/client', () => ({
  api: {
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    health: vi.fn(),
    spaces: {
      mine: spacesMocks.mine,
      deactivate: spacesMocks.deactivate,
    },
    availability: {
      mine: availabilityMocks.mine,
      remove: availabilityMocks.remove,
    },
    photoUrl: (photo: { contentUrl: string }) => `http://test${photo.contentUrl}`,
  },
  tokenStore: {
    get access() {
      return 'access-1';
    },
    get refresh() {
      return 'refresh-1';
    },
    save: vi.fn(),
    clear: vi.fn(),
  },
}));

const user: User = {
  id: 'u1',
  email: 'host@example.com',
  firstName: 'Holly',
  lastName: 'Host',
  phone: null,
  role: 'USER',
};

function renderParking() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <MyParking />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('MyParking', () => {
  it('shows the empty state when the host has no spaces', async () => {
    const { api } = await import('../api/client');
    vi.mocked(api.me).mockResolvedValue(user);
    spacesMocks.mine.mockResolvedValue([]);
    availabilityMocks.mine.mockResolvedValue([]);

    renderParking();

    expect(await screen.findByText('No parking spaces yet')).toBeInTheDocument();
    expect(screen.getByText('Add your space')).toBeInTheDocument();
  });

  it('lists the host spaces with status badges', async () => {
    const { api } = await import('../api/client');
    vi.mocked(api.me).mockResolvedValue(user);
    spacesMocks.mine.mockResolvedValue([
      {
        id: 's1',
        hostId: 'u1',
        label: 'B17',
        address: '123 Wacker Dr',
        city: 'Chicago',
        state: 'IL',
        zipCode: '60601',
        latitude: 41.8858,
        longitude: -87.6189,
        areaLabel: 'West Loop',
        parkingType: 'ASSIGNED_SPACE',
        description: null,
        vehicleSizes: ['SEDAN'],
        heightLimitInches: null,
        covered: true,
        evCharging: false,
        parkingInstructions: null,
        authorizationConfirmed: true,
        authorizationConfirmedAt: '2026-09-24T00:00:00Z',
        active: true,
        photos: [],
        displayState: 'AVAILABLE',
        createdAt: '2026-09-24T00:00:00Z',
        updatedAt: '2026-09-24T00:00:00Z',
      },
    ]);
    availabilityMocks.mine.mockResolvedValue([]);

    renderParking();

    expect(await screen.findByText('B17 · West Loop')).toBeInTheDocument();
    expect(screen.getByText('AVAILABLE')).toBeInTheDocument();
    expect(screen.getByText('Manage')).toBeInTheDocument();
  });

  it('shows the currently-sharing section with live and upcoming shares', async () => {
    const { api } = await import('../api/client');
    vi.mocked(api.me).mockResolvedValue(user);
    spacesMocks.mine.mockResolvedValue([
      {
        id: 's1',
        hostId: 'u1',
        label: 'B17',
        areaLabel: 'West Loop',
        active: true,
        photos: [],
        displayState: 'AVAILABLE',
      },
    ]);
    const now = Date.now();
    availabilityMocks.mine.mockResolvedValue([
      {
        id: 'w1',
        spaceId: 's1',
        startsAt: new Date(now - 10 * 60 * 1000).toISOString(),
        endsAt: new Date(now + 2 * 3600 * 1000).toISOString(),
        source: 'MANUAL',
        hourlyRateCents: 300,
        live: true,
        createdAt: new Date(now - 10 * 60 * 1000).toISOString(),
      },
      {
        id: 'w2',
        spaceId: 's1',
        startsAt: new Date(now + 3 * 3600 * 1000).toISOString(),
        endsAt: new Date(now + 5 * 3600 * 1000).toISOString(),
        source: 'MANUAL',
        hourlyRateCents: null,
        live: false,
        createdAt: new Date().toISOString(),
      },
    ]);

    renderParking();

    expect(await screen.findByText('Currently sharing')).toBeInTheDocument();
    expect(screen.getByText(/Available until/)).toBeInTheDocument();
    expect(screen.getByText('$3.00/hr')).toBeInTheDocument();
    // Only the not-started share can be removed.
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
  });

  it('removes an upcoming share after confirmation', async () => {
    const { api } = await import('../api/client');
    vi.mocked(api.me).mockResolvedValue(user);
    spacesMocks.mine.mockResolvedValue([]);
    const now = Date.now();
    const upcoming = {
      id: 'w2',
      spaceId: 's1',
      startsAt: new Date(now + 3 * 3600 * 1000).toISOString(),
      endsAt: new Date(now + 5 * 3600 * 1000).toISOString(),
      source: 'MANUAL',
      hourlyRateCents: null,
      live: false,
      createdAt: new Date().toISOString(),
    };
    availabilityMocks.mine.mockResolvedValue([upcoming]);
    availabilityMocks.remove.mockResolvedValue(undefined);

    renderParking();

    const remove = await screen.findByRole('button', { name: 'Remove' });
    fireEvent.click(remove);
    expect(screen.getByRole('button', { name: 'Tap again to remove' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Tap again to remove' }));

    await waitFor(() => {
      expect(availabilityMocks.remove).toHaveBeenCalledWith('w2');
    });
  });

  it('shows a friendly error when loading fails', async () => {
    const { api } = await import('../api/client');
    vi.mocked(api.me).mockResolvedValue(user);
    spacesMocks.mine.mockRejectedValue(
      new ApiError(500, { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred.' }),
    );

    renderParking();

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('An unexpected error occurred.');
    });
  });
});
