type Notification = { message: string; link?: string };

type Listener = (n: Notification) => void;
const listeners = new Set<Listener>();

export const notify = (n: Notification) => {
  for (const l of listeners) l(n);
  if (listeners.size === 0) {
    // eslint-disable-next-line no-console
    console.info('[notify]', n.message, n.link ?? '');
  }
};

export const onNotify = (l: Listener) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export const notifyAuditId = (auditId?: string) => {
  if (!auditId) return;
  const link = `/admin/audit?event=${encodeURIComponent(auditId)}`;
  notify({ message: `İşlem kaydedildi • AuditId: ${auditId}`, link });
};
