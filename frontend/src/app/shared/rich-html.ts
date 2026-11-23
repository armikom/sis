import DOMPurify, { type Config } from 'dompurify';

/**
 * Rich (HTML) content of questions and groups is written by course staff and shown to students, so it is sanitized
 * both when stored from the editor and whenever it is rendered. Scripts, event handlers, forms and external frames
 * are removed; inline images (data: URIs) and basic formatting stay.
 */
const CONFIG: Config = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe', 'object', 'embed', 'link', 'meta'],
  FORBID_ATTR: ['id', 'onerror', 'onload'],
  ADD_ATTR: ['target'],
};

export function sanitizeHtml(html: string | null | undefined): string {
  if (!html) return '';
  const out = DOMPurify.sanitize(html, CONFIG);
  return typeof out === 'string' ? out : String(out);
}

/** True when the content has visible text or an image (an editor left with only `<p><br></p>` counts as empty). */
export function hasRichContent(html: string | null | undefined): boolean {
  if (!html) return false;
  const box = document.createElement('div');
  box.innerHTML = sanitizeHtml(html);
  return !!box.textContent?.trim() || box.querySelector('img, table') !== null;
}

/** First line of the content as plain text, for compact summaries. */
export function richSummary(html: string | null | undefined, max = 160): string {
  if (!html) return '';
  const box = document.createElement('div');
  box.innerHTML = sanitizeHtml(html);
  return (box.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, max);
}
