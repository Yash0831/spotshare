import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import Dialog from './Dialog';

function renderDialog(onClose: () => void = vi.fn()) {
  render(
    <Dialog label="Test dialog" onClose={onClose}>
      <button type="button">First</button>
      <button type="button">Second</button>
      <button type="button">Third</button>
    </Dialog>,
  );
  return { onClose };
}

describe('Dialog', () => {
  it('announces itself as a modal dialog with the given label', () => {
    renderDialog();
    const dialog = screen.getByRole('dialog', { name: 'Test dialog' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('closes on overlay click but not on panel click', () => {
    const onClose = vi.fn();
    renderDialog(onClose);
    fireEvent.click(screen.getByText('Second'));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('dialog', { name: 'Test dialog' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape', () => {
    const onClose = vi.fn();
    renderDialog(onClose);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('moves focus to the first control on open and restores the trigger on close', () => {
    const onClose = vi.fn();
    render(
      <>
        <button type="button">Trigger</button>
      </>,
    );
    const trigger = screen.getByText('Trigger');
    trigger.focus();
    const { unmount } = render(
      <Dialog label="Test dialog" onClose={onClose}>
        <button type="button">Inside</button>
      </Dialog>,
    );
    expect(screen.getByText('Inside')).toHaveFocus();
    unmount();
    expect(trigger).toHaveFocus();
  });

  it('traps Tab inside the dialog, wrapping last → first and first → last', () => {
    renderDialog();
    const buttons = screen.getAllByRole('button');
    const first = buttons[0];
    const last = buttons[buttons.length - 1];

    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(first).toHaveFocus();

    first.focus();
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
  });

  it('locks body scroll while open and restores it on close', () => {
    const { unmount } = render(
      <Dialog label="Test dialog" onClose={vi.fn()}>
        <button type="button">Inside</button>
      </Dialog>,
    );
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });
});
