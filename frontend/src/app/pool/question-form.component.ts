import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PoolApiService } from './pool-api.service';
import { PoolContext } from './pool-context.service';
import { PM } from './pool-messages';
import { LETTERS } from './pool-util';
import type { ChapterDetail, Letter, QuestionInput } from './pool.models';
import { errorMessage } from '../shared/errors';
import { TopicPickerComponent } from '../shared/topics/topic-picker.component';
import { poolTopics, Topic } from '../shared/topics/topic';

type TextField = Exclude<keyof QuestionInput, 'chapter_id' | 'correct' | 'topic_ids'>;

function emptyForm(chapterId: number | null): QuestionInput {
  return {
    chapter_id: chapterId, stem: '', option_a: '', option_b: '', option_c: '', option_d: '', correct: '',
    rationale_a: '', rationale_b: '', rationale_c: '', rationale_d: '', reference: '', topic_ids: [],
  };
}

/** Write a question (…/chapters/:chapterId/new) or change one (…/questions/:id/edit). */
@Component({
  selector: 'app-pool-question-form',
  imports: [FormsModule, RouterLink, TopicPickerComponent],
  template: `
    <p class="small">
      @if (id()) { <a [routerLink]="[ctx.base(), 'questions', id()]">{{ pm.back }}</a> }
      @else { <a [routerLink]="[ctx.base(), 'chapters', form.chapter_id]" [queryParams]="{ tab: 'questions' }">{{ pm.back }}</a> }
    </p>
    <h1>{{ id() ? pm.titleEdit : pm.titleNew }}</h1>
    @if (chapter(); as ch) { <p class="muted">{{ ch.title }}</p> }

    <details class="card" open>
      <summary><b>{{ pm.goodTitle }}</b></summary>
      <ul class="tips">
        <li>It asks <b>one</b> thing from this chapter, in short and clear English.</li>
        <li>It makes the reader <b>think</b>: use a small example or situation. Do not only ask for a definition.</li>
        <li>Exactly <b>one</b> option is correct. The three wrong options look possible to someone who did not study.</li>
        <li>No "all of the above" and no "none of the above".</li>
        <li>You explain why each wrong option is wrong. This is the part where you learn the most.</li>
      </ul>
    </details>

    @if (ready()) {
      <form (ngSubmit)="save()" ngNativeValidate>
        <label for="stem">{{ pm.stem }}</label>
        <textarea id="stem" name="stem" [(ngModel)]="form.stem" rows="4" maxlength="800" required></textarea>

        <h2>{{ pm.options }}</h2>
        <p class="hint">{{ pm.optionsHint }}</p>
        @for (l of letters; track l) {
          <div class="card">
            <div class="row">
              <b>{{ l }}</b>
              <label class="inline"><input type="radio" name="correct" [value]="l" [(ngModel)]="form.correct" required /> {{ pm.isCorrect }}</label>
            </div>
            <input type="text" class="mt-sm" [name]="'option_' + l" [ngModel]="text('option', l)" (ngModelChange)="setText('option', l, $event)"
              maxlength="300" required [placeholder]="pm.option + ' ' + l" [attr.aria-label]="pm.option + ' ' + l" />
            <label [for]="'why_' + l" class="small">{{ !form.correct ? pm.whyAny : form.correct === l ? pm.whyCorrect : pm.whyWrong }}</label>
            <textarea [id]="'why_' + l" [name]="'rationale_' + l" [ngModel]="text('rationale', l)" (ngModelChange)="setText('rationale', l, $event)"
              rows="2" maxlength="600" required></textarea>
          </div>
        }

        <label for="reference">{{ pm.reference }} <span class="hint">{{ pm.referenceHint }}</span></label>
        <input id="reference" name="reference" type="text" [(ngModel)]="form.reference" maxlength="200" required />

        @if (courseTopics().length) {
          <label id="topics-label">{{ pm.topics }} <span class="hint">{{ pm.topicsHint }}</span></label>
          <app-topic-picker [topics]="courseTopics()" [(selected)]="topicIds" [firstChapterId]="form.chapter_id" [label]="pm.topics" />
        }

        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
        <p class="row">
          <button type="submit" class="primary" [disabled]="busy()">{{ id() ? pm.save : pm.send }}</button>
          @if (!ctx.staff()) { <span class="small muted">{{ pm.after }}</span> }
        </p>
      </form>
    } @else if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    }
  `,
})
export class QuestionFormComponent {
  private readonly api = inject(PoolApiService);
  private readonly router = inject(Router);
  readonly ctx = inject(PoolContext);
  readonly pm = PM.form;
  readonly letters = LETTERS;
  /** Set on …/questions/:id/edit */
  readonly id = input<string | undefined>(undefined);
  /** Set on …/chapters/:chapterId/new */
  readonly chapterId = input<string | undefined>(undefined);

  form: QuestionInput = emptyForm(null);
  readonly chapter = signal<ChapterDetail | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly ready = signal(false);
  /** All topics of the course (GET …/topics); empty when the course has none or the request fails. */
  readonly courseTopics = signal<Topic[]>([]);
  readonly topicIds = signal<number[]>([]);

  constructor() {
    effect(() => {
      const id = this.id();
      const chapterId = Number(this.chapterId()) || null;
      untracked(() => this.load(id, chapterId));
    });
  }

  private async load(id: string | undefined, chapterId: number | null) {
    this.form = emptyForm(chapterId);
    this.topicIds.set([]);
    this.ready.set(!id);
    this.error.set('');
    this.api.courseTopics(this.ctx.courseId()).then(list => this.courseTopics.set(poolTopics(list)), () => this.courseTopics.set([]));
    try {
      if (id) {
        const q = await this.api.question(id);
        const f = emptyForm(q.chapter_id);
        for (const key of Object.keys(f) as (keyof QuestionInput)[]) {
          const v = q[key as keyof typeof q];
          if (key !== 'chapter_id' && key !== 'topic_ids') (f as unknown as Record<string, unknown>)[key] = v ?? '';
        }
        this.form = f;
        this.topicIds.set((q.topics ?? []).map(t => t.id));
        this.ready.set(true);
      }
      if (this.form.chapter_id) this.chapter.set(await this.api.chapter(this.form.chapter_id));
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  text(kind: 'option' | 'rationale', l: Letter): string {
    return this.form[`${kind}_${l.toLowerCase()}` as TextField];
  }

  setText(kind: 'option' | 'rationale', l: Letter, v: string) {
    this.form[`${kind}_${l.toLowerCase()}` as TextField] = v;
  }

  async save() {
    this.busy.set(true);
    this.error.set('');
    try {
      let id: string | number | undefined = this.id();
      const courseId = this.ctx.courseId();
      // Only when the picker was shown: otherwise the current links stay as they are.
      if (this.courseTopics().length) this.form.topic_ids = this.topicIds();
      else delete this.form.topic_ids;
      if (!id) id = (await this.api.createQuestion(this.form)).id;
      else if (this.ctx.staff()) await this.api.staffUpdateQuestion(courseId, id, this.form);
      else await this.api.updateQuestion(id, this.form);
      this.router.navigate([this.ctx.base(), 'questions', id]);
    } catch (e) {
      this.error.set(errorMessage(e));
      this.busy.set(false);
    }
  }
}
