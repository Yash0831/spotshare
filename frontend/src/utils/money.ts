/**
 * Cents-safe money formatting. Money travels as integer cents everywhere —
 * never floats — so formatting just divides by 100 at the very edge.
 */

/** `$3.00` for 300 cents; "Free" when the price is null (a free share). */
export function formatCents(cents: number | null | undefined): string {
  if (cents == null) return 'Free';
  return `$${(cents / 100).toFixed(2)}`;
}

/** `$3.00/hr` or "Free" — for share prices and price breakdowns. */
export function formatRate(cents: number | null | undefined): string {
  if (cents == null) return 'Free';
  return `${formatCents(cents)}/hr`;
}
