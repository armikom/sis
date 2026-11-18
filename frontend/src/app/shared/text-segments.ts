export interface TextSegment { code: boolean; text: string }

/**
 * Question text as plain text: fenced code blocks (```) become their own segments so they can be shown in a monospace
 * block; everything else stays as written (shown with white-space: pre-wrap, never as HTML).
 */
export function textSegments(text: string | null | undefined): TextSegment[] {
  if (!text) return [];
  const out: TextSegment[] = [];
  const re = /^```[^\n]*\n([\s\S]*?)^```[ \t]*$/gm;
  let last = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const before = text.slice(last, m.index).replace(/\n$/, '');
    if (before.trim()) out.push({ code: false, text: before });
    out.push({ code: true, text: m[1].replace(/\n$/, '') });
    last = m.index + m[0].length;
  }
  const rest = text.slice(last).replace(/^\n/, '');
  if (rest.trim()) out.push({ code: false, text: rest });
  return out;
}
