import { Suspense, lazy } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import BottomNav from './components/BottomNav';
import LoadingScreen from './components/LoadingScreen';

// Route-level code splitting: each page becomes its own chunk so the first
// paint (Home + auth) stays light — the Leaflet map bundle only loads when
// the driver opens Explore or Park Now.
const Explore = lazy(() => import('./pages/Explore'));
const Home = lazy(() => import('./pages/Home'));
const Login = lazy(() => import('./pages/Login'));
const MyParking = lazy(() => import('./pages/MyParking'));
const MyReservations = lazy(() => import('./pages/MyReservations'));
const ParkNow = lazy(() => import('./pages/ParkNow'));
const Profile = lazy(() => import('./pages/Profile'));
const PublicSpaceDetail = lazy(() => import('./pages/PublicSpaceDetail'));
const Register = lazy(() => import('./pages/Register'));
const ReservationDetail = lazy(() => import('./pages/ReservationDetail'));
const Reserve = lazy(() => import('./pages/Reserve'));
const SpaceDetail = lazy(() => import('./pages/SpaceDetail'));
const SpaceWizard = lazy(() => import('./pages/SpaceWizard'));

function RequireGuest({ children }: { children: JSX.Element }) {
  const { user, loading } = useAuth();
  if (loading) return <LoadingScreen message="Checking your session…" />;
  return user ? <Navigate to="/" replace /> : children;
}

function RequireAuth({ children }: { children: JSX.Element }) {
  const { user, loading } = useAuth();
  if (loading) return <LoadingScreen message="Checking your session…" />;
  return user ? children : <Navigate to="/login" replace />;
}

/**
 * Phone-style shell for tabbed pages without their own container: centers
 * the content, pads it clear of the bottom nav, and renders the nav.
 */
function TabShell({ children }: { children: JSX.Element }) {
  return (
    <div className="mx-auto w-full max-w-md px-4 pb-24 pt-4">
      {children}
      <BottomNav />
    </div>
  );
}

/** For tabbed pages that already have their own container: nav only. */
function TabNav() {
  return (
    <>
      <div className="h-20" aria-hidden />
      <BottomNav />
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-[1300] focus:rounded-lg focus:bg-white focus:px-4 focus:py-2 focus:font-semibold focus:text-sky-700 focus:shadow-lg"
        >
          Skip to main content
        </a>
        <div id="main-content" tabIndex={-1} className="min-h-screen bg-slate-50 text-slate-900 antialiased">
          <Suspense fallback={<LoadingScreen />}>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route
              path="/login"
              element={
                <RequireGuest>
                  <Login />
                </RequireGuest>
              }
            />
            <Route
              path="/register"
              element={
                <RequireGuest>
                  <Register />
                </RequireGuest>
              }
            />
            {/* Public discovery — no login required */}
            <Route
              path="/explore"
              element={
                <TabShell>
                  <Explore />
                </TabShell>
              }
            />
            <Route
              path="/park-now"
              element={
                <TabShell>
                  <ParkNow />
                </TabShell>
              }
            />
            <Route
              path="/spaces/:id"
              element={
                <TabShell>
                  <PublicSpaceDetail />
                </TabShell>
              }
            />
            <Route
              path="/spaces/:id/reserve"
              element={
                <RequireAuth>
                  <TabShell>
                    <Reserve />
                  </TabShell>
                </RequireAuth>
              }
            />
            {/* Driver pages — login required */}
            <Route
              path="/reservations"
              element={
                <RequireAuth>
                  <TabShell>
                    <MyReservations />
                  </TabShell>
                </RequireAuth>
              }
            />
            <Route
              path="/reservations/:id"
              element={
                <RequireAuth>
                  <TabShell>
                    <ReservationDetail />
                  </TabShell>
                </RequireAuth>
              }
            />
            {/* Host pages — login required */}
            <Route
              path="/parking"
              element={
                <RequireAuth>
                  <>
                    <MyParking />
                    <TabNav />
                  </>
                </RequireAuth>
              }
            />
            <Route
              path="/parking/new"
              element={
                <RequireAuth>
                  <SpaceWizard />
                </RequireAuth>
              }
            />
            <Route
              path="/parking/:id"
              element={
                <RequireAuth>
                  <SpaceDetail />
                </RequireAuth>
              }
            />
            <Route
              path="/profile"
              element={
                <RequireAuth>
                  <TabShell>
                    <Profile />
                  </TabShell>
                </RequireAuth>
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
          </Suspense>
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}
