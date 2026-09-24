/**
 * Full-screen loading state: brand line + spinner + message. Used while the
 * session is being checked, as the Suspense fallback for lazily loaded
 * routes, and anywhere a whole screen is still loading — so the user never
 * stares at a blank page.
 */
export default function LoadingScreen({ message = 'Loading…' }: { message?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex min-h-screen flex-col items-center justify-center bg-slate-50 px-4"
    >
      <p className="text-sm font-semibold uppercase tracking-widest text-sky-700">SpotShare</p>
      <div
        aria-hidden
        className="mt-5 h-10 w-10 animate-spin rounded-full border-4 border-slate-200 border-t-sky-700"
      />
      <p className="mt-4 text-sm text-slate-500">{message}</p>
    </div>
  );
}
