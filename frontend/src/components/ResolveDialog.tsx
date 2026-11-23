import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import type { Alert } from '../api/types';

interface Props {
  alert: Alert | null;
  zoneName: string;
  error: string | null;
  busy: boolean;
  onCancel(): void;
  onResolve(note: string): void;
}

const MAX = 500;

/** Native modal dialog: focus is trapped and Escape closes it; focus returns to the opener. */
export function ResolveDialog({ alert, zoneName, error, busy, onCancel, onResolve }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const [note, setNote] = useState('');
  const id = useId();

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (alert && !d.open) {
      setNote('');
      d.showModal();
    } else if (!alert && d.open) {
      d.close();
    }
  }, [alert]);

  const trimmed = note.trim();
  const length = [...note].length;
  const invalid = trimmed.length === 0 || length > MAX;

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!invalid) onResolve(note);
  }

  return (
    <dialog ref={ref} aria-labelledby={`${id}-title`} onCancel={(e) => { e.preventDefault(); onCancel(); }}>
      {alert && (
        <form onSubmit={submit}>
          <h2 id={`${id}-title`}>Resolve alert</h2>
          <p>
            Asset <strong>{alert.assetId}</strong> in <strong>{zoneName}</strong>
          </p>
          <label htmlFor={`${id}-note`}>Resolution note (required, up to {MAX} characters)</label>
          <textarea
            id={`${id}-note`}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={5}
            required
            aria-invalid={note.length > 0 && invalid}
            aria-describedby={`${id}-count${error ? ` ${id}-err` : ''}`}
            autoFocus
          />
          <p id={`${id}-count`} className={length > MAX ? 'error' : 'muted'}>
            {length} / {MAX} characters
          </p>
          {error && (
            <p id={`${id}-err`} role="alert" className="error">
              {error}
            </p>
          )}
          <div className="actions">
            <button type="button" className="secondary" onClick={onCancel}>
              Cancel
            </button>
            <button type="submit" disabled={invalid || busy}>
              {busy ? 'Resolving…' : 'Resolve'}
            </button>
          </div>
        </form>
      )}
    </dialog>
  );
}
