import { useEffect, useRef } from 'react';

interface DialogProps {
  /** Accessible name announced when the dialog opens. */
  label: string;
  onClose: () => void;
  children: React.ReactNode;
}

/**
 * Accessible modal dialog (bottom sheet on phones, centered card on larger
 * screens). Escape closes it, focus moves inside on open, Tab cycles within
 * the dialog, focus returns to the trigger on close, and the background page
 * doesn't scroll while it's open.
 */
export default function Dialog({ label, onClose, children }: DialogProps) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  // Move focus inside on open; restore the trigger's focus on close.
  useEffect(() => {
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    const overlay = overlayRef.current;
    const first = overlay?.querySelector<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    (first ?? overlay)?.focus();
    return () => {
      previouslyFocused.current?.focus?.();
    };
  }, []);

  // Escape closes; Tab is trapped inside the dialog.
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const overlay = overlayRef.current;
      if (!overlay) return;
      const focusables = Array.from(
        overlay.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  // Don't let the page behind the sheet scroll (mobile Safari included).
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div
      ref={overlayRef}
      role="dialog"
      aria-modal="true"
      aria-label={label}
      tabIndex={-1}
      onClick={onClose}
      className="fixed inset-0 z-[1300] flex items-end justify-center bg-slate-900/50 sm:items-center sm:p-4"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="max-h-[90dvh] w-full max-w-md overflow-y-auto rounded-t-2xl bg-white p-6 pb-8 shadow-xl sm:rounded-2xl"
      >
        {children}
      </div>
    </div>
  );
}
