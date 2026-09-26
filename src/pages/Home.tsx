import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/**
 * Home screen: brand line + quick actions. Discovery (Explore, Park Now) is
 * public; hosting and account live behind login.
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
      <p className="text-gradient text-sm font-bold uppercase tracking-widest">SpotShare</p>
      <h1 className="mt-2 text-3xl font-bold text-slate-900">
        Borrow a <span className="text-gradient">parking spot</span>.
      </h1>
      <p className="mt-2 text-slate-600">
        Neighbors share the spots they&apos;re not using — right when you need one.
      </p>

      <div className="mt-8 grid grid-cols-2 gap-3">
        <Link
          to="/park-now"
          className="rounded-2xl bg-gradient-to-br from-violet-600 via-indigo-600 to-fuchsia-600 px-4 py-5 text-center font-semibold text-white shadow-xl shadow-violet-600/30 transition hover:brightness-110"
        >
          <span className="block text-3xl" aria-hidden>
            🅿️
          </span>
          Park now
        </Link>
        <Link
          to="/explore"
          className="rounded-2xl bg-gradient-to-br from-amber-400 to-orange-500 px-4 py-5 text-center font-semibold text-white shadow-xl shadow-amber-500/30 transition hover:brightness-110"
        >
          <span className="block text-3xl" aria-hidden>
            🔍
          </span>
          Plan parking
        </Link>
      </div>

      {loading ? (
        <div className="mt-8 rounded-lg bg-slate-100 px-4 py-6 text-center text-slate-500">
          Checking your session…
        </div>
      ) : user ? (
        <div className="card-lift mt-6 p-5">
          <p className="text-sm text-slate-500">Signed in as</p>
          <p className="mt-1 text-lg font-semibold text-slate-900">
            {user.firstName} {user.lastName}
          </p>
          <p className="text-sm text-slate-600">{user.email}</p>
          <Link
            to="/parking"
            className="btn-brand mt-4 block w-full rounded-lg px-4 py-2.5 text-center text-sm font-semibold text-white"
          >
            My Parking
          </Link>
          <button
            onClick={onLogout}
            disabled={busy}
            className="mt-2 w-full rounded-lg border border-violet-200 bg-white px-4 py-2.5 text-sm font-semibold text-violet-700 disabled:opacity-60"
          >
            {busy ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          <Link
            to="/register"
            className="btn-brand block w-full rounded-lg px-4 py-3 text-center text-base font-semibold text-white"
          >
            Create an account
          </Link>
          <Link
            to="/login"
            className="block w-full rounded-lg border-2 border-violet-200 bg-white px-4 py-3 text-center text-base font-semibold text-violet-700"
          >
            Log in
          </Link>
        </div>
      )}

      <p className="mt-10 text-center text-xs text-slate-400">
        Hosts share the exact address only after you reserve — listings show an approximate
        location.
      </p>
    </div>
  );
}
