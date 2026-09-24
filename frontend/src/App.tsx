import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Home from './pages/Home';
import Login from './pages/Login';
import MyParking from './pages/MyParking';
import Register from './pages/Register';
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
            <Route
              path="/parking"
              element={
                <RequireAuth>
                  <MyParking />
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
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}
