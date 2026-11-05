// In-memory session state for the prototype. Nothing persists beyond the theme.

import { data } from './data.js';

const savedTheme = (() => {
  try { return localStorage.getItem('flash.console.theme'); } catch { return null; }
})();

export const state = {
  theme: savedTheme === 'light' ? 'light' : 'dark',
  requests: [...data.requests],
  invites: [...data.invites],
  announcements: [...data.announcements],
  revokedIds: new Set(),
  auditVerifiedAt: data.audit.verifiedAt,
  auditEntries: data.audit.total,
  announcementsPublished: 0,
};

const bus = new EventTarget();

export function subscribe(fn) {
  bus.addEventListener('change', (event) => fn(event.detail));
  return () => bus.removeEventListener('change', fn);
}

export function notify(detail = {}) {
  bus.dispatchEvent(new CustomEvent('change', { detail }));
}

export function applyTheme() {
  document.documentElement.dataset.theme = state.theme;
  try { localStorage.setItem('flash.console.theme', state.theme); } catch { /* private mode */ }
}

export function toggleTheme() {
  state.theme = state.theme === 'dark' ? 'light' : 'dark';
  applyTheme();
  notify({ type: 'theme' });
  return state.theme;
}

export function waitingCount() {
  return state.requests.length;
}

export function pendingInvites() {
  return state.invites.filter((i) => i.state === 'PENDING').length;
}

export function deviceStatus(device) {
  return state.revokedIds.has(device.id) ? 'REVOKED' : device.status;
}

export function deviceIsOnline(device) {
  return state.revokedIds.has(device.id) ? false : device.online;
}

export function recordAudit({ action, label, detail, actor = data.admin.short }) {
  const entry = {
    action,
    label,
    actor,
    detail,
    minutesAgo: 0,
    index: state.auditEntries + 1,
    at: new Date(),
  };
  data.audit.entries.unshift(entry);
  state.auditEntries += 1;
  notify({ type: 'audit-entry', action });
  return entry;
}

export function revokeDevice(id, name = 'Device') {
  state.revokedIds.add(id);
  recordAudit({ action: 'device.revoke', label: 'Device revoked', detail: `${name} · revoked by ${data.admin.short}` });
  notify({ type: 'device', id });
}

export function approveRequest(id) {
  const index = state.requests.findIndex((r) => r.id === id);
  if (index === -1) return null;
  const [request] = state.requests.splice(index, 1);
  recordAudit({ action: 'join.approve', label: 'Join request approved', detail: `${request.name} · ${request.department}` });
  notify({ type: 'request', id });
  return request;
}

export function declineRequest(id) {
  const index = state.requests.findIndex((r) => r.id === id);
  if (index === -1) return null;
  const [request] = state.requests.splice(index, 1);
  recordAudit({ action: 'join.decline', label: 'Join request declined', detail: `${request.name} · ${request.department}` });
  notify({ type: 'request', id });
  return request;
}

export function revokeInvite(id) {
  const invite = state.invites.find((item) => item.id === id);
  if (!invite) return null;
  invite.state = 'REVOKED';
  recordAudit({ action: 'invite.revoke', label: 'Invite revoked', detail: `${invite.name} · unused code cancelled` });
  notify({ type: 'invite-revoked', id });
  return invite;
}

export function createInvite({ name, department, role, days }) {
  const invite = {
    id: `inv-new-${state.invites.length + 1}`,
    name: name || 'New colleague',
    department,
    role,
    state: 'PENDING',
    createdDaysAgo: 0,
    expiresDays: days,
    code: `MRD-${(state.invites.length * 7 + 19).toString(36).toUpperCase().padStart(4, '0')}-${(state.invites.length * 11 + 23).toString(36).toUpperCase().padStart(4, '0')}`,
    usedBy: null,
  };
  state.invites.unshift(invite);
  recordAudit({ action: 'invite.create', label: 'Invite created', detail: `${invite.name} · ${invite.department}` });
  notify({ type: 'invite', id: invite.id });
  return invite;
}

export function publishAnnouncement({ title, body, scope }) {
  const announcement = {
    id: `ann-new-${state.announcements.length + 1}`,
    title,
    body,
    author: data.admin.name,
    scope,
    pinned: false,
    minutesAgo: 0,
    audience: scope === 'All departments' ? 128 : data.departments.find((d) => d.name === scope)?.members || 0,
  };
  state.announcements.unshift(announcement);
  state.announcementsPublished += 1;
  recordAudit({ action: 'announcement.publish', label: 'Announcement published', detail: `${announcement.title} · ${audienceLabel(scope)}` });
  notify({ type: 'announcement', id: announcement.id });
  return announcement;
}

function audienceLabel(scope) {
  return scope === 'All departments' ? 'all departments' : scope;
}

export function markAuditVerified() {
  state.auditVerifiedAt = new Date();
  const entries = state.auditEntries;
  recordAudit({ action: 'audit.verify', label: 'Audit chain verified', actor: 'hub', detail: `${entries} entries · chain intact` });
  notify({ type: 'audit' });
}
