import { describe, it, expect } from 'vitest';
import { renderMarkdown } from '../../pool/pool-util';
import {
  findSection, fromApiTopic, fromPoolTopic, groupByChapter, pickLang, stripLeadingHeading, topicFragment, topicHeading, topicLabel,
} from './topic';

const pool = { id: 9, chapter_id: 3, chapter_number: 5, chapter_title: 'Week 5', number: 4, title_en: 'Infeasible test requirements', title_tr: 'Olanaksız test gereksinimleri' };

describe('topics', () => {
  it('maps both wire shapes to one model', () => {
    const a = fromPoolTopic(pool);
    const b = fromApiTopic({ id: 9, chapterId: 3, chapterNumber: 5, chapterTitle: 'Week 5', number: 4, titleEn: 'Infeasible test requirements', titleTr: 'Olanaksız test gereksinimleri' });
    expect(a).toEqual(b);
    expect(fromPoolTopic({ id: 1, number: 2, title_en: 'X', title_tr: '' }, { id: 7, number: 1, title: 'Week 1' }))
      .toEqual({ id: 1, chapterId: 7, chapterNumber: 1, chapterTitle: 'Week 1', number: 2, titleEn: 'X', titleTr: '' });
  });

  it('builds the chip label, the heading and a fragment that matches the rendered chapter text', () => {
    const t = fromPoolTopic(pool);
    expect(topicLabel(t, 'en')).toBe('W5 · 4. Infeasible test requirements');
    expect(topicHeading({ ...t, titleTr: '' }, 'tr')).toBe('4. Infeasible test requirements');
    const box = document.createElement('div');
    box.innerHTML = renderMarkdown('# Week 5\n\n## 4. Olanaksız test gereksinimleri\n\ntext');
    expect(findSection(box, topicFragment(t, 'tr'))?.textContent).toBe('4. Olanaksız test gereksinimleri');
    expect(findSection(box, topicFragment(t, 'en'), true)).toBeNull();
    // Same section number, other title (or the other language) → the section is still found.
    expect(findSection(box, topicFragment(t, 'en'))?.tagName).toBe('H2');
    expect(findSection(box, 'unknown-section')).toBeNull();
  });

  it('picks the wanted language only when it has content', () => {
    expect(pickLang('tr', 'en text', '')).toBe('en');
    expect(pickLang('tr', 'en text', 'tr text')).toBe('tr');
    expect(pickLang('en', '', '')).toBe('en');
  });

  it('drops the section heading the dialog already shows', () => {
    expect(stripLeadingHeading('## 4. Title\n\nBody', 4)).toBe('\nBody');
    expect(stripLeadingHeading('## 5. Other\n\nBody', 4)).toBe('## 5. Other\n\nBody');
    expect(stripLeadingHeading('Body', 4)).toBe('Body');
  });

  it('groups by chapter with the own chapter first', () => {
    const t = (id: number, ch: number, n: number) => fromPoolTopic({ id, chapter_id: ch, chapter_number: ch, chapter_title: `W${ch}`, number: n, title_en: 'x', title_tr: '' });
    const g = groupByChapter([t(1, 1, 2), t(2, 2, 1), t(3, 1, 1), t(4, 3, 1)], 2);
    expect(g.map(x => x.chapterId)).toEqual([2, 1, 3]);
    expect(g[1].topics.map(x => x.id)).toEqual([3, 1]);
  });
});
