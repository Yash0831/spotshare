import { NavLink } from 'react-router-dom';

/**
 * Bottom tab bar: Explore · Park Now · Reservations · Arrivals ·
 * My Parking · Profile. Park Now is the prominent center action.
 * Each tab has its own accent color; the active tab gets a tinted pill.
 */
const TABS = [
  { to: '/explore', label: 'Explore', icon: '🔍', active: 'bg-amber-100 text-amber-800' },
  { to: '/park-now', label: 'Park Now', icon: '🅿️', center: true, active: 'bg-violet-600 text-white shadow-lg shadow-violet-600/30' },
  { to: '/reservations', label: 'Reserved', icon: '🎟️', active: 'bg-rose-100 text-rose-800' },
  { to: '/arrivals', label: 'Arrivals', icon: '📋', active: 'bg-emerald-100 text-emerald-800' },
  { to: '/parking', label: 'My Parking', icon: '🏠', active: 'bg-sky-100 text-sky-800' },
  { to: '/profile', label: 'Profile', icon: '👤', active: 'bg-violet-100 text-violet-800' },
];

export default function BottomNav() {
  return (
    <nav
      aria-label="Main navigation"
      className="bottom-nav fixed inset-x-0 bottom-0 bg-white/95 backdrop-blur"
    >
      <div
        aria-hidden
        className="h-1 bg-gradient-to-r from-violet-500 via-fuchsia-500 to-amber-400"
      />
      <div className="mx-auto grid max-w-md grid-cols-6 border-t border-slate-100 px-1 py-1">
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 rounded-2xl px-1 py-1.5 text-[11px] font-semibold transition ${
                isActive ? tab.active : 'text-slate-500 hover:text-slate-700'
              }`
            }
          >
            <span aria-hidden className={tab.center ? 'text-2xl' : 'text-xl'}>
              {tab.icon}
            </span>
            {tab.label}
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
