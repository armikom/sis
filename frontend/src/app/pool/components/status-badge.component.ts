import { Component, computed, input } from '@angular/core';
import { PM } from '../pool-messages';
import type { QuestionStatus } from '../pool.models';

@Component({
  selector: 'pool-status-badge',
  template: `<span class="badge" [class]="'badge ' + (label()[1] ?? '')">{{ label()[0] }}</span>`,
})
export class StatusBadgeComponent {
  readonly status = input.required<QuestionStatus | string>();
  readonly label = computed(() => PM.status[this.status() as QuestionStatus] ?? [this.status(), '']);
}
