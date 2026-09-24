import { describe, expect, it } from 'vitest';
import { formatCents, formatRate } from './money';

describe('formatCents', () => {
  it('formats integer cents as dollars', () => {
    expect(formatCents(300)).toBe('$3.00');
    expect(formatCents(1)).toBe('$0.01');
    expect(formatCents(10000)).toBe('$100.00');
  });

  it('renders null as Free', () => {
    expect(formatCents(null)).toBe('Free');
    expect(formatCents(undefined)).toBe('Free');
  });
});

describe('formatRate', () => {
  it('appends /hr for paid shares', () => {
    expect(formatRate(300)).toBe('$3.00/hr');
  });

  it('renders null as Free', () => {
    expect(formatRate(null)).toBe('Free');
  });
});
