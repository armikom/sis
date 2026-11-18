import { Component, computed, inject, input } from '@angular/core';
import { MSG } from '../messages';
import { TopicPreviewService } from './topic-preview.service';
import { DocLang, preferredLang, Topic, topicLabel } from './topic';

/** Topics as small chips ("W5 · 4. Infeasible test requirements"); a click opens the preview popup. */
@Component({
  selector: 'app-topic-chips',
  template: `
    @if (topics().length) {
      <ul class="topic-chips" [attr.aria-label]="label() || null">
        @for (t of topics(); track t.id) {
          <li>
            <button type="button" class="topic-chip" [title]="msg.chipTitle" (click)="open(t, $event)">
              <span class="topic-chip-dot" aria-hidden="true">◆</span>{{ text(t) }}
            </button>
          </li>
        }
      </ul>
    }
  `,
  styles: [`
    :host { display: block; }
    .topic-chips { list-style: none; margin: 6px 0; padding: 0; display: flex; flex-wrap: wrap; gap: 6px; }
    .topic-chip {
      display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
      font: inherit; font-size: 0.82rem; font-weight: 500; line-height: 1.3; text-align: left;
      padding: 5px 11px; min-height: 32px; border-radius: 999px; cursor: pointer;
      border: 1px solid var(--c-primary-light); background: var(--c-primary-light); color: var(--c-primary);
      overflow-wrap: anywhere;
    }
    .topic-chip:hover { border-color: var(--c-primary); text-decoration: none; }
    .topic-chip:focus-visible { outline: 2px solid var(--c-primary); outline-offset: 2px; }
    .topic-chip-dot { font-size: 0.6rem; opacity: 0.7; }
  `],
})
export class TopicChipsComponent {
  private readonly preview = inject(TopicPreviewService);
  readonly msg = MSG.topics;
  readonly topics = input<Topic[]>([]);
  /** Title language; default: the remembered reading language. */
  readonly lang = input<DocLang | null>(null);
  /** Accessible name of the list. */
  readonly label = input('');
  private readonly effectiveLang = computed(() => this.lang() ?? preferredLang());

  text(t: Topic): string {
    return topicLabel(t, this.effectiveLang());
  }

  open(t: Topic, e: Event) {
    // Chips may sit inside a link card: do not follow it.
    e.preventDefault();
    e.stopPropagation();
    this.preview.open(t.id);
  }
}
