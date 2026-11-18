import { Component, computed, input } from '@angular/core';
import { PM } from '../pool-messages';
import { asList, parseAiReview } from '../pool-util';

/** Result of the automatic (AI) check of a question. */
@Component({
  selector: 'pool-ai-review',
  template: `
    @if (status() === 'RUNNING') {
      <div class="note">{{ pm.running }}</div>
    } @else if (status() === 'FAILED') {
      <div class="note amber">{{ pm.failed }}</div>
    } @else if (status() === 'DONE' && r(); as r) {
      <div class="note" [class.green]="r.verdict === 'OK'" [class.amber]="r.verdict !== 'OK'">
        <b>{{ pm.title }} {{ r.verdict === 'OK' ? pm.ok : pm.improve }}</b>
        <span class="small muted">{{ pm.disclaimer }}</span>
        @if (r.key_correct === 'no') {
          <div class="error">{{ pm.keyWrong }}</div>
        } @else if (r.key_correct === 'unsure') {
          <div class="small">{{ pm.keyUnsure }}</div>
        }
        @if (issues().length) {
          <ul class="issues">
            @for (issue of issues(); track $index) { <li>{{ issue }}</li> }
          </ul>
        }
        @if (duplicates()) { <div class="small">{{ pm.duplicate(duplicates()) }}</div> }
        @if (r.english_suggestion) { <div class="small"><b>{{ pm.simpler }}</b> {{ r.english_suggestion }}</div> }
      </div>
    }
  `,
})
export class AiReviewComponent {
  readonly status = input<string | null | undefined>(null);
  readonly review = input<string | null | undefined>(null);
  readonly pm = PM.ai;
  readonly r = computed(() => parseAiReview(this.review()));
  readonly issues = computed(() => asList(this.r()?.issues));
  readonly duplicates = computed(() => asList(this.r()?.duplicate_of).map(d => '#' + d).join(', '));
}
