import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { User } from '../api/types';
import SpaceWizard from './SpaceWizard';

vi.mock('../api/client', () => ({
  api: {
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    health: vi.fn(),
    spaces: {
      mine: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      deactivate: vi.fn(),
      uploadPhoto: vi.fn(),
      deletePhoto: vi.fn(),
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

async function renderWizard() {
  const { api } = await import('../api/client');
  vi.mocked(api.me).mockResolvedValue(user);
  render(
    <MemoryRouter initialEntries={['/parking/new']}>
      <AuthProvider>
        <SpaceWizard />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('SpaceWizard', () => {
  it('starts on the Location step', async () => {
    await renderWizard();

    expect(await screen.findByText('Step 1 of 5: Location')).toBeInTheDocument();
    expect(screen.getByLabelText('Street address')).toBeInTheDocument();
  });

  it('blocks Continue until the location fields are valid', async () => {
    const actor = userEvent.setup();
    await renderWizard();

    await actor.click(await screen.findByText('Continue'));

    // Still on step 1, with friendly per-field errors.
    expect(await screen.findByText('Step 1 of 5: Location')).toBeInTheDocument();
    expect(
      screen.getByText('Give your space a short label, like a space number.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Enter a valid ZIP code, e.g. 60601.')).toBeInTheDocument();
  });

  it('advances to the Parking type step with valid location input', async () => {
    const actor = userEvent.setup();
    await renderWizard();

    await actor.type(await screen.findByLabelText('Space label or number'), 'B17');
    await actor.type(screen.getByLabelText('Street address'), '123 Wacker Dr');
    await actor.type(screen.getByLabelText('City'), 'Chicago');
    await actor.type(screen.getByLabelText('State'), 'IL');
    await actor.type(screen.getByLabelText('ZIP code'), '60601');
    await actor.type(screen.getByLabelText('Latitude'), '41.8858');
    await actor.type(screen.getByLabelText('Longitude'), '-87.6189');
    await actor.type(screen.getByLabelText('Public area name'), 'West Loop');
    await actor.click(screen.getByText('Continue'));

    expect(await screen.findByText('Step 2 of 5: Parking type')).toBeInTheDocument();
  });
});
