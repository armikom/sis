import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector, inject, Injectable } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { TopicPreviewDialogComponent } from './topic-preview-dialog.component';

/** Opens the topic preview popup (one at a time) at the end of <body>; any navigation closes it. */
@Injectable({ providedIn: 'root' })
export class TopicPreviewService {
  private readonly appRef = inject(ApplicationRef);
  private readonly env = inject(EnvironmentInjector);
  private ref: ComponentRef<TopicPreviewDialogComponent> | null = null;
  private host: HTMLElement | null = null;

  constructor() {
    inject(Router).events.subscribe(e => { if (e instanceof NavigationStart) this.close(); });
  }

  open(topicId: number) {
    this.close();
    const host = document.createElement('div');
    document.body.appendChild(host);
    const ref = createComponent(TopicPreviewDialogComponent, { environmentInjector: this.env, hostElement: host });
    ref.setInput('topicId', topicId);
    ref.instance.closed.subscribe(() => this.close());
    this.appRef.attachView(ref.hostView);
    this.ref = ref;
    this.host = host;
  }

  close() {
    const ref = this.ref;
    const host = this.host;
    this.ref = null;
    this.host = null;
    if (ref) {
      this.appRef.detachView(ref.hostView);
      ref.destroy();
    }
    host?.remove();
  }
}
