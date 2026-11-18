import { describe, it, expect } from 'vitest';
import type { MyResultDetailDto } from '../core/api.models';
import { isMissed, resultView, textSegments } from './my-result.logic';

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
});
