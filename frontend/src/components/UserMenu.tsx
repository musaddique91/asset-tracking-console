import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import type { Session } from '../session';

/**
 * Menu button (WAI-ARIA pattern): the button always shows who is signed in; the menu holds the
 * account actions. Enter/Space/ArrowDown open and focus the first item, Escape closes and returns
 * focus to the button, Tab or a click outside closes.
 */
export function UserMenu({ session, onSignOut }: { session: Session; onSignOut(): void }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const firstItem = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    firstItem.current?.focus();
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  function onButtonKey(e: KeyboardEvent<HTMLButtonElement>) {
    if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setOpen(true);
    }
  }

  function onMenuKey(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
      button.current?.focus();
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  }

  const role = session.role.charAt(0) + session.role.slice(1).toLowerCase();

  return (
    <div className="user-menu" ref={root}>
      <button
        ref={button}
        type="button"
        className="user-button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={`${id}-menu`}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onButtonKey}
      >
        <span className="avatar" aria-hidden="true">
          {session.username.slice(0, 2).toUpperCase()}
        </span>
        <span className="user-text">
          <span className="sr-only">Signed in as </span>
          <strong>{session.username}</strong>
          <span className="role">{role}</span>
        </span>
        <span className="caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div id={`${id}-menu`} role="menu" aria-label="Account" className="menu" onKeyDown={onMenuKey}>
          <div className="menu-header" role="presentation">
            Signed in as <strong>{session.username}</strong>
            <br />
            <span className="muted">{role}</span>
          </div>
          <button ref={firstItem} type="button" role="menuitem" className="menu-item" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
