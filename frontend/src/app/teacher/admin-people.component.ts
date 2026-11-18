import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MSG } from '../shared/messages';
import { errorMessage } from '../shared/errors';
import type { PersonDto, PersonRequest } from '../core/api.models';

/** System admin: list / search / create / edit / soft-delete people (replaces the old teachers screen). */
@Component({
  selector: 'app-admin-people',
  imports: [FormsModule],
  template: `
    <div class="container">
      <div class="page-header">
        <h1>{{ msg.admin.title }}</h1>
        <button class="btn btn-primary" (click)="startCreate()">{{ msg.admin.create }}</button>
      </div>

      @if (showForm()) {
        <div class="card mb-md">
          <form (ngSubmit)="save()">
            <div class="form-grid">
              <div class="form-group"><label for="p-name">{{ msg.admin.displayName }}</label><input id="p-name" class="form-input" [(ngModel)]="form.fullName" name="n" required maxlength="200" /></div>
              <div class="form-group"><label for="p-email">{{ msg.admin.email }}</label><input id="p-email" class="form-input" type="email" [(ngModel)]="form.email" name="e" autocomplete="off" /></div>
              <div class="form-group"><label for="p-no">{{ msg.admin.studentNo }}</label><input id="p-no" class="form-input" [(ngModel)]="form.studentNo" name="s" inputmode="numeric" /></div>
              <div class="form-group"><label for="p-user">{{ msg.admin.username }}</label><input id="p-user" class="form-input" [(ngModel)]="form.username" name="u" autocomplete="off" /></div>
              <div class="form-group"><label for="p-pass">{{ msg.admin.password }}</label><input id="p-pass" class="form-input" type="password" [(ngModel)]="form.password" name="p" minlength="8" autocomplete="new-password" [placeholder]="editId ? 'Değiştirmek için doldurun' : 'İsteğe bağlı, en az 8 karakter'" /></div>
            </div>
            <div class="flex-gap mb-md" style="flex-wrap:wrap">
              <label class="flex-gap"><input type="checkbox" [(ngModel)]="form.active" name="a" [disabled]="editId === auth.user()?.id" /> {{ msg.admin.enabled }}</label>
              <label class="flex-gap"><input type="checkbox" [(ngModel)]="form.systemAdmin" name="sa" [disabled]="editId === auth.user()?.id" /> {{ msg.admin.systemAdmin }}</label>
            </div>
            @if (error()) { <p class="form-error" role="alert">{{ error() }}</p> }
            <div class="flex-gap">
              <button class="btn btn-primary" type="submit" [disabled]="!form.fullName.trim()">Kaydet</button>
              <button class="btn btn-outline" type="button" (click)="cancel()">İptal</button>
            </div>
          </form>
        </div>
      }

      <form class="flex-gap mb-md" (ngSubmit)="load()">
        <input class="form-input" type="search" [(ngModel)]="query" name="q" [placeholder]="msg.admin.search" [attr.aria-label]="msg.admin.search" style="max-width:360px" />
        <button class="btn btn-outline btn-sm" type="submit">Ara</button>
      </form>
      @if (error() && !showForm()) { <p class="form-error" role="alert">{{ error() }}</p> }
      @if (people().length === 0) { <p class="text-secondary">{{ msg.admin.noData }}</p> }

      @if (people().length > 0) {
        <div style="overflow-x:auto"><table>
          <thead><tr>
            <th>{{ msg.admin.displayName }}</th><th>{{ msg.admin.email }}</th><th>{{ msg.admin.studentNo }}</th>
            <th>{{ msg.admin.username }}</th><th>Takma ad</th><th>Durum</th><th></th>
          </tr></thead>
          <tbody>
            @for (p of people(); track p.id) {
              <tr [class.text-secondary]="p.deleted || !p.active">
                <td>{{ p.fullName }} @if (p.systemAdmin) { <span class="badge badge-info">Yönetici</span> }</td>
                <td>{{ p.email }}</td>
                <td class="text-mono">{{ p.studentNo }}</td>
                <td>{{ p.username }}</td>
                <td>{{ p.nickname }}</td>
                <td>
                  @if (p.deleted) { <span class="badge badge-danger">{{ msg.admin.deleted }}</span> }
                  @else { <span class="badge" [class.badge-success]="p.active" [class.badge-danger]="!p.active">{{ p.active ? 'Aktif' : 'Pasif' }}</span> }
                </td>
                <td class="flex-gap">
                  <button class="btn btn-outline btn-sm" (click)="startEdit(p)">Düzenle</button>
                  @if (!p.deleted) {
                    <button class="btn btn-outline btn-sm text-danger" (click)="remove(p)" [disabled]="p.id === auth.user()?.id">Sil</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table></div>
      }
    </div>
  `,
  styles: [`
    .form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 0 var(--space-md); }
  `],
})
export class AdminPeopleComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);
  readonly msg = MSG;
  readonly people = signal<PersonDto[]>([]);
  readonly error = signal('');
  readonly showForm = signal(false);
  query = '';
  editId: number | null = null;
  form = AdminPeopleComponent.empty();

  static empty() {
    return { fullName: '', email: '', studentNo: '', username: '', password: '', active: true, systemAdmin: false };
  }

  async ngOnInit() { await this.load(); }

  async load() {
    this.error.set('');
    try { this.people.set(await this.api.getPersons(this.query.trim())); }
    catch (e) { this.error.set(errorMessage(e)); }
  }

  startCreate() {
    this.editId = null;
    this.form = AdminPeopleComponent.empty();
    this.error.set('');
    this.showForm.set(true);
  }

  startEdit(p: PersonDto) {
    this.editId = p.id;
    this.form = {
      fullName: p.fullName, email: p.email ?? '', studentNo: p.studentNo ?? '', username: p.username ?? '',
      password: '', active: p.active && !p.deleted, systemAdmin: p.systemAdmin,
    };
    this.error.set('');
    this.showForm.set(true);
  }

  cancel() {
    this.showForm.set(false);
    this.editId = null;
    this.error.set('');
  }

  async save() {
    this.error.set('');
    const f = this.form;
    // Empty strings clear e-mail / number / username on the server; the password is only sent when typed.
    const body: PersonRequest = {
      fullName: f.fullName.trim(), email: f.email.trim(), studentNo: f.studentNo.trim(), username: f.username.trim(),
      active: f.active, systemAdmin: f.systemAdmin,
    };
    if (f.password) body.password = f.password;
    try {
      if (this.editId) await this.api.updatePerson(this.editId, body);
      else await this.api.createPerson(body);
      this.cancel();
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  async remove(p: PersonDto) {
    if (!confirm(this.msg.admin.deleteConfirm)) return;
    try {
      await this.api.deletePerson(p.id);
      await this.load();
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}
