import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { QuestionViewComponent } from './question-view.component';
import { ChaptersHomeComponent } from './chapters-home.component';
import type { PoolMe, QuestionDetail } from './pool.models';

const me: PoolMe = {
  course: { id: 7, code: 'SENG 403', name: 'QA', term: '2026-FALL', language: 'en' },
  staff: false, nickname: 'blue-fox', questions_per_chapter: 2, ask_ai_per_day: 5, ai_enabled: true,
};
const question: QuestionDetail = {
  id: 5, chapter_id: 1, stem: 'Which one?', option_a: 'a1', option_b: 'b1', option_c: 'c1', option_d: 'd1',
  status: 'APPROVED', author: 'red-cat', mine: false, revealed: false,
};

const fakeApi = {
  me: async () => me,
  chapters: async () => [{ id: 1, number: 1, title: 'Week 1', open: true, has_doc_en: true, has_doc_tr: false, approved: 4, my_questions: 1, my_attempts: 2, my_correct: 1 }],
  question: async () => question,
  chapterQuestions: async () => [],
  attempt: async (_id: string, selected: string) => ({ ...question, revealed: true, my_selected: selected, correct: 'B', rationale_a: 'ra', rationale_b: 'rb', rationale_c: 'rc', rationale_d: 'rd', reference: 'W1' }),
  comments: async () => [],
};

async function settle(f: { whenStable: () => Promise<unknown>; detectChanges: () => void }) {
  for (let i = 0; i < 3; i++) { await f.whenStable(); f.detectChanges(); }
}

describe('pool views (render smoke)', () => {
  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), PoolContext, { provide: PoolApiService, useValue: fakeApi }],
    });
    await TestBed.inject(PoolContext).load(7);
  });

  it('shows chapters with my progress', async () => {
    const f = TestBed.createComponent(ChaptersHomeComponent);
    await settle(f);
    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Week 1');
    expect(text).toContain('My questions 1/2');
    expect(text).toContain('blue-fox');
  });

  it('hides the key until the first answer, then reveals it', async () => {
    const f = TestBed.createComponent(QuestionViewComponent);
    f.componentRef.setInput('id', '5');
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Which one?');
    expect(el.querySelectorAll('input[type=radio]').length).toBe(4);
    expect(el.textContent).not.toContain('rb');

    f.componentInstance.selected.set('A');
    await f.componentInstance.answer();
    await settle(f);
    expect(el.textContent).toContain('Not correct.');
    expect(el.textContent).toContain('rb');
    expect(el.textContent).toContain('Discussion');
  });
});
