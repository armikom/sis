import { Component, computed, input, model } from '@angular/core';
import { MSG } from '../messages';
import { DocLang, groupByChapter, preferredLang, Topic, topicHeading } from './topic';

/** Multi-select of topics, grouped by chapter (`firstChapterId` first and open). Two-way `selected` (topic ids). */
@Component({
  selector: 'app-topic-picker',
  template: `
    @if (!topics().length) {
      <p class="tpk-muted">{{ msg.pickerNone }}</p>
    } @else {
      <div class="tpk" role="group" [attr.aria-label]="label() || null">
        <div class="tpk-count">{{ msg.pickerSelected(selected().length) }}</div>
        @for (g of groups(); track g.chapterId; let first = $first) {
          <details class="tpk-group" [open]="(first && firstChapterId() != null) || groups().length === 1 || hasSelected(g.topics)">
            <summary>{{ g.label }} <span class="tpk-muted">({{ countIn(g.topics) }}/{{ g.topics.length }})</span></summary>
            @for (t of g.topics; track t.id) {
              <label class="tpk-item">
                <input type="checkbox" [checked]="isOn(t.id)" (change)="toggle(t.id, $any($event.target).checked)" />
                <span>{{ heading(t) }}</span>
              </label>
            }
          </details>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .tpk { border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: 6px 10px; background: var(--c-surface); }
    .tpk-count { font-size: 0.82rem; color: var(--c-text-secondary); margin: 2px 0 4px; }
    .tpk-group { border-top: 1px solid var(--c-border); padding: 4px 0; }
    .tpk-group:first-of-type { border-top: 0; }
    .tpk-group summary { cursor: pointer; font-weight: 600; font-size: 0.9rem; padding: 6px 0; }
    .tpk-item {
      display: flex !important; align-items: flex-start; gap: 8px; margin: 0 !important; padding: 6px 2px;
      font-weight: 400 !important; font-size: 0.9rem; cursor: pointer; min-height: 32px;
    }
    .tpk-item input { margin-top: 3px; width: auto; flex: none; }
    .tpk-muted { color: var(--c-text-secondary); font-weight: 400; font-size: 0.85rem; }
  `],
})
export class TopicPickerComponent {
  readonly msg = MSG.topics;
  readonly topics = input<Topic[]>([]);
  readonly selected = model<number[]>([]);
  readonly firstChapterId = input<number | null>(null);
  readonly lang = input<DocLang | null>(null);
  readonly label = input('');

  readonly groups = computed(() => groupByChapter(this.topics(), this.firstChapterId()));
  private readonly selectedSet = computed(() => new Set(this.selected()));

  heading(t: Topic) { return topicHeading(t, this.lang() ?? preferredLang()); }
  isOn(id: number) { return this.selectedSet().has(id); }
  countIn(ts: Topic[]) { return ts.filter(t => this.selectedSet().has(t.id)).length; }
  hasSelected(ts: Topic[]) { return this.countIn(ts) > 0; }

  toggle(id: number, on: boolean) {
    const rest = this.selected().filter(x => x !== id);
    this.selected.set(on ? [...rest, id] : rest);
  }
}
