/**
 * Placeholder rows shown while list content loads. Skeletons keep the layout
 * stable (no content jumping when the data arrives) and tell screen readers
 * what's happening through the polite live region.
 */
function SkeletonBlock({ className = '' }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={`animate-pulse rounded-lg bg-slate-200 ${className}`}
    />
  );
}

/** Mimics the shape of a reservation row in My Reservations. */
export function ReservationSkeleton() {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-label="Loading reservations"
      className="space-y-3"
    >
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className="rounded-xl border border-slate-200 bg-white p-4"
        >
          <SkeletonBlock className="h-5 w-2/3" />
          <SkeletonBlock className="mt-2 h-4 w-1/2" />
          <SkeletonBlock className="mt-3 h-9 w-28" />
        </div>
      ))}
    </div>
  );
}

/** Mimics the shape of a space card in Explore search results. */
export function SpaceCardSkeleton() {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-label="Loading parking spots"
      className="space-y-3"
    >
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className="overflow-hidden rounded-xl border border-slate-200 bg-white"
        >
          <SkeletonBlock className="h-36 w-full rounded-none" />
          <div className="p-4">
            <SkeletonBlock className="h-5 w-3/4" />
            <SkeletonBlock className="mt-2 h-4 w-1/2" />
          </div>
        </div>
      ))}
    </div>
  );
}
