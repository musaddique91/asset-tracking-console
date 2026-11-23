import { useRef, type KeyboardEvent } from 'react';

export interface TabDef<T extends string> {
  id: T;
  label: string;
  /** Optional count shown next to the label (for example active alerts). */
  badge?: number;
}

interface Props<T extends string> {
  idPrefix: string;
  label: string;
  tabs: TabDef<T>[];
  selected: T;
  onSelect(id: T): void;
}

/**
 * WAI-ARIA tabs: one tab stop for the whole list (roving tabindex), Left/Right/Home/End move
 * and activate, each tab controls a tabpanel with matching ids (see panelId / tabId).
 */
export function Tabs<T extends string>({ idPrefix, label, tabs, selected, onSelect }: Props<T>) {
  const refs = useRef(new Map<T, HTMLButtonElement>());

  function move(e: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = -1;
    if (e.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    if (next < 0) return;
    e.preventDefault();
    const tab = tabs[next];
    if (!tab) return;
    onSelect(tab.id);
    refs.current.get(tab.id)?.focus();
  }

  return (
    <div role="tablist" aria-label={label} className="tablist">
      {tabs.map((t, i) => {
        const active = t.id === selected;
        return (
          <button
            key={t.id}
            ref={(el) => {
              if (el) refs.current.set(t.id, el);
              else refs.current.delete(t.id);
            }}
            type="button"
            role="tab"
            id={tabId(idPrefix, t.id)}
            aria-selected={active}
            aria-controls={panelId(idPrefix, t.id)}
            tabIndex={active ? 0 : -1}
            className="tab"
            onClick={() => onSelect(t.id)}
            onKeyDown={(e) => move(e, i)}
          >
            {t.label}
            {t.badge !== undefined && (
              <span className="tab-badge">
                <span className="sr-only">, </span>
                {t.badge}
                <span className="sr-only"> active</span>
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export const tabId = (prefix: string, id: string) => `${prefix}-tab-${id}`;
export const panelId = (prefix: string, id: string) => `${prefix}-panel-${id}`;
