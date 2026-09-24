import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import MyParking from './MyParking';
import { ApiError, User } from '../api/types';

const spacesMocks = vi.hoisted(() => ({
  mine: vi.fn(),
  deactivate: vi.fn(),
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
        createdAt: '2026-09-24T00:00:00Z',
        updatedAt: '2026-09-24T00:00:00Z',
      },
    ]);

    renderParking();

    expect(await screen.findByText('B17 · West Loop')).toBeInTheDocument();
    expect(screen.getByText('PRIVATE')).toBeInTheDocument();
    expect(screen.getByText('Manage')).toBeInTheDocument();
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
