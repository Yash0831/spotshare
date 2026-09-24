import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { api } from '../api/client';
import { User } from '../api/types';

/**
 * Profile: who you're signed in as, plus sign out. Discovery (Explore, Park
 * Now) stays public; hosting and reservations live behind this tab.
 */
export default function Profile() {
  const navigate = useNavigate();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api
      .me()
      .then((u) => {
        if (!cancelled) setUser(u);
      })
      .catch(() => {
        if (!cancelled) setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signOut = async () => {
    await api.logout().catch(() => undefined);
    navigate('/login');
  };

  if (loading) {
    return <p className="text-center text-sm text-slate-500">Loading…</p>;
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold text-slate-900">Profile</h1>

      {user && (
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <p className="text-lg font-semibold text-slate-900">
            {user.firstName} {user.lastName}
          </p>
          <p className="text-sm text-slate-600">{user.email}</p>
          {user.phone && <p className="text-sm text-slate-600">{user.phone}</p>}
        </div>
      )}

      <Link
        to="/parking"
        className="block rounded-xl border border-slate-200 bg-white p-4 text-sm font-medium text-slate-800"
      >
        🏠 My Parking — spaces and shares
      </Link>

      <button
        type="button"
        onClick={signOut}
        className="w-full rounded-lg border border-slate-300 py-2.5 font-semibold text-slate-700"
      >
        Sign out
      </button>
    </div>
  );
}
