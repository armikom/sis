/** localStorage prefix of the unsaved answer-entry sheets (exam/exam-entry-draft.ts); cleared on logout. */
export const EXAM_DRAFT_PREFIX = 'sis.examEntryDraft.';

export function getItem(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}

export function setItem(key: string, value: string): void {
  try { localStorage.setItem(key, value); } catch { /* ignore */ }
}

export function removeItem(key: string): void {
  try { localStorage.removeItem(key); } catch { /* ignore */ }
}

/** Removes every entry whose key starts with `prefix` (e.g. per-exam drafts on logout). */
export function removeByPrefix(prefix: string): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k?.startsWith(prefix)) keys.push(k);
    }
    keys.forEach(k => localStorage.removeItem(k));
  } catch { /* ignore */ }
}
