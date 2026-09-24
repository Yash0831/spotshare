import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import BottomNav from './components/BottomNav';
import Explore from './pages/Explore';
import Home from './pages/Home';
import Login from './pages/Login';
import MyParking from './pages/MyParking';
import MyReservations from './pages/MyReservations';
import ParkNow from './pages/ParkNow';
import Profile from './pages/Profile';
import PublicSpaceDetail from './pages/PublicSpaceDetail';
import Register from './pages/Register';
import ReservationDetail from './pages/ReservationDetail';
import Reserve from './pages/Reserve';
import SpaceDetail from './pages/SpaceDetail';
import SpaceWizard from './pages/SpaceWizard';

function RequireGuest({ children }: { children: JSX.Element }) {
  const { user, loading } = useAuth();
  if (loading) return null;
  return user ? <Navigate to="/" replace /> : children;
}

function RequireAuth({ children }: { children: JSX.Element }) {
  const { user, loading } = useAuth();
  if (loading) return null;
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
        <div className="min-h-screen bg-slate-50 text-slate-900 antialiased">
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
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}
