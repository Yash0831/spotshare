import { NavLink } from 'react-router-dom';

/**
 * Bottom tab bar: Explore · Park Now · My Parking · Profile.
 * Park Now is the prominent center action.
 */
const TABS = [
  { to: '/explore', label: 'Explore', icon: '🔍' },
  { to: '/park-now', label: 'Park Now', icon: '🅿️', center: true },
  { to: '/parking', label: 'My Parking', icon: '🏠' },
  { to: '/profile', label: 'Profile', icon: '👤' },
];

export default function BottomNav() {
  return (
    <nav
      aria-label="Main navigation"
      className="bottom-nav fixed inset-x-0 bottom-0 border-t border-slate-200 bg-white/95 backdrop-blur"
    >
      <div className="mx-auto grid max-w-md grid-cols-4">
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium ${
                isActive ? 'text-sky-700' : 'text-slate-500'
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
