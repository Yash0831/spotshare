import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ApiError } from '../api/types';
import Login from './Login';

const apiMocks = vi.hoisted(() => ({
  login: vi.fn(),
  save: vi.fn(),
  clear: vi.fn(),
}));

vi.mock('../api/client', () => ({
  api: {
    login: apiMocks.login,
    register: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    health: vi.fn(),
  },
  tokenStore: {
    get access() {
      return null;
    },
    get refresh() {
      return null;
    },
    save: apiMocks.save,
    clear: apiMocks.clear,
  },
}));

const authResponse = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 'u1',
    email: 'ada@example.com',
    firstName: 'Ada',
    lastName: 'Lovelace',
    phone: null,
    role: 'USER',
  },
};

function renderLogin() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <Login />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('Login page', () => {
  beforeEach(() => {
    apiMocks.login.mockReset();
    apiMocks.save.mockReset();
  });

  it('validates empty fields before calling the API', async () => {
    renderLogin();

    await userEvent.click(screen.getByRole('button', { name: /^log in$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/email and password/i);
    expect(apiMocks.login).not.toHaveBeenCalled();
  });

  it('logs in with the entered credentials and stores the tokens', async () => {
    apiMocks.login.mockResolvedValue(authResponse);
    renderLogin();

    await userEvent.type(screen.getByLabelText(/email/i), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'secret123');
    await userEvent.click(screen.getByRole('button', { name: /^log in$/i }));

    expect(apiMocks.login).toHaveBeenCalledWith({
      email: 'ada@example.com',
      password: 'secret123',
    });
    expect(apiMocks.save).toHaveBeenCalledWith('access-1', 'refresh-1');
  });

  it('shows the server message when credentials are wrong', async () => {
    apiMocks.login.mockRejectedValue(
      new ApiError(401, {
        code: 'INVALID_CREDENTIALS',
        message: 'Email or password is incorrect.',
      }),
    );
    renderLogin();

    await userEvent.type(screen.getByLabelText(/email/i), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /^log in$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/incorrect/i);
  });
});
