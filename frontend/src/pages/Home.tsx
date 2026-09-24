import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/**
 * Phase 1 home screen: brand line + account state. The parking workflows
 * (search, share, reservations) arrive in later phases.
 */
export default function Home() {
  const { user, loading, logout } = useAuth();
  const [busy, setBusy] = useState(false);

  async function onLogout() {
    setBusy(true);
    await logout();
    setBusy(false);
  }

  return (
    <div className="mx-auto w-full max-w-md px-4 py-10">
      <p className="text-sm font-semibold uppercase tracking-widest text-sky-700">SpotShare</p>
      <h1 className="mt-2 text-3xl font-bold text-slate-900">Borrow a parking spot.</h1>
      <p className="mt-2 text-slate-600">
        Neighbors share the spots they're not using — right when you need one.
      </p>

      {loading ? (
        <div className="mt-8 rounded-lg bg-slate-100 px-4 py-6 text-center text-slate-500">
          Checking your session…
        </div>
      ) : user ? (
        <div className="mt-8 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <p className="text-sm text-slate-500">Signed in as</p>
          <p className="mt-1 text-lg font-semibold text-slate-900">
            {user.firstName} {user.lastName}
          </p>
          <p className="text-sm text-slate-600">{user.email}</p>
          <button
            onClick={onLogout}
            disabled={busy}
            className="mt-4 w-full rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-60"
          >
            {busy ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      ) : (
        <div className="mt-8 space-y-3">
          <Link
            to="/register"
            className="block w-full rounded-lg bg-sky-700 px-4 py-3 text-center text-base font-semibold text-white shadow-sm"
          >
            Create an account
          </Link>
          <Link
            to="/login"
            className="block w-full rounded-lg border border-slate-300 px-4 py-3 text-center text-base font-semibold text-slate-700"
          >
            Log in
          </Link>
        </div>
      )}

      <p className="mt-10 text-center text-xs text-slate-400">
        Parking search and sharing arrive in the next phase — accounts are ready today.
      </p>
    </div>
  );
}
