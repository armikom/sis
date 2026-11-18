import { describe, it, expect } from 'vitest';
import type { MyResultDetailDto } from '../core/api.models';
import { correctShareText, isMissed, optionRows, possessiveSuffix, questionStats, resultView, sortByMostMissed, textSegments } from './my-result.logic';
import type { MyResultQuestionDto } from '../core/api.models';

const topic = { id: 1, chapterId: 2, chapterNumber: 5, chapterTitle: 'W5', number: 4, titleEn: 'A', titleTr: 'B' };

describe('my result', () => {
  it('counts graded questions below full points as missed, ungraded not', () => {
    expect(isMissed({ points: 0, maxPoints: 4 })).toBe(true);
    expect(isMissed({ points: 4, maxPoints: 4 })).toBe(false);
    expect(isMissed({ points: 5, maxPoints: 10 })).toBe(true);
    expect(isMissed({ points: null, maxPoints: 10 })).toBe(false);
  });

  it('keeps review topics in server order and sorts questions by position', () => {
    const d: MyResultDetailDto = {
      examId: 1, title: 'Vize', examDate: null, points: 4, maxPoints: 8,
      questions: [
        { position: 2, text: 'b', type: 'CHOICE', myAnswer: 'A', correctChoice: 'B', correct: false, points: 0, maxPoints: 4, topics: [topic] },
        { position: 1, text: 'a', type: 'CHOICE', myAnswer: 'C', correctChoice: 'C', correct: true, points: 4, maxPoints: 4 },
      ],
      reviewTopics: [{ topic: { ...topic, id: 7 }, missed: 0, total: 1 }, { topic, missed: 1, total: 1 }],
    };
    const v = resultView(d);
    expect(v.questions.map(q => q.position)).toEqual([1, 2]);
    expect(v.reviewTopics.map(r => r.topic.id)).toEqual([7, 1]);
    expect(v.missedCount).toBe(1);
    expect(v.hasTopics).toBe(true);
    expect(v.questions[1].topicList[0].titleEn).toBe('A');
  });

  it('splits fenced code blocks out of the question text', () => {
    expect(textSegments('What prints?\n```java\nint x = 1;\n  x++;\n```\nPick one.')).toEqual([
      { code: false, text: 'What prints?' },
      { code: true, text: 'int x = 1;\n  x++;' },
      { code: false, text: 'Pick one.' },
    ]);
    expect(textSegments('plain <b>text</b>')).toEqual([{ code: false, text: 'plain <b>text</b>' }]);
    expect(textSegments(null)).toEqual([]);
  });

  const base: MyResultQuestionDto = { position: 1, text: 'q', type: 'CHOICE', myAnswer: 'B', correctChoice: 'C', correct: false, points: 0, maxPoints: 4 };

  it('shows each letter once with text, class share, key and my choice', () => {
    const rows = optionRows({
      ...base,
      options: [{ letter: 'A', text: 'one', rationale: null }, { letter: 'B', text: 'two', rationale: 'no' }, { letter: 'C', text: 'three', rationale: 'yes' }],
      stats: { answered: 10, correctShare: 0.6, averagePoints: 2.4, choiceCounts: { A: 1, B: 2, C: 6, D: 0, blank: 1 } },
    });
    expect(rows.map(r => r.letter)).toEqual(['A', 'B', 'C', 'D']);
    expect(rows[1]).toEqual({ letter: 'B', text: 'two', rationale: 'no', correct: false, mine: true, count: 2, share: 0.2 });
    expect(rows[2].correct).toBe(true);
    expect(rows[3].text).toBeNull();
    expect(questionStats({ ...base, stats: { answered: 10, correctShare: 0.6, averagePoints: null, choiceCounts: { A: 9, blank: 1 } } })).toMatchObject({ blank: 1, blankShare: 0.1 });
  });

  it('falls back to the letter-only line without option texts and counts; no stats → nothing', () => {
    expect(optionRows(base)).toEqual([]);
    expect(optionRows({ ...base, stats: { answered: 3, correctShare: null, averagePoints: 4, choiceCounts: {} } })).toEqual([]);
    expect(questionStats(base)).toBeNull();
    const rows = optionRows({ ...base, options: [{ letter: 'A', text: 'only A', rationale: null }] });
    expect(rows.map(r => [r.letter, r.count])).toEqual([['A', null], ['B', null], ['C', null]]);
  });

  it('orders by the lowest class correct share, unknown last', () => {
    const q = (position: number, share: number | null) => ({ position, statsView: share == null ? null : { answered: 1, correctShare: share, averagePoints: null, blank: null, blankShare: null } });
    expect(sortByMostMissed([q(1, 0.9), q(2, null), q(3, 0.2), q(4, 0.2)]).map(x => x.position)).toEqual([3, 4, 1, 2]);
  });

  it('writes the class share in Turkish with the right suffix', () => {
    expect(correctShareText(0.72)).toBe("Sınıfın %72'si doğru yanıtladı");
    expect([0, 3, 6, 10, 40, 60, 90, 100].map(possessiveSuffix)).toEqual(['ı', 'ü', 'sı', 'u', 'ı', 'ı', 'ı', 'ü']);
    expect(correctShareText(null)).toBeNull();
  });
});
