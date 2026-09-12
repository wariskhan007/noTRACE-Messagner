/**
 * RAM-only offline mailbox. Every queued item is an opaque, already
 * end-to-end-encrypted blob (the server cannot decrypt it) and is
 * permanently discarded after MAILBOX_TTL_MS whether or not it was
 * ever delivered. Nothing here is ever written to disk.
 */

const MAILBOX_TTL_MS = 72 * 60 * 60 * 1000; // 72 hours, matches the plan's hard cap
const MAX_ITEMS_PER_RECIPIENT = 200; // guard against unbounded memory growth / abuse

interface QueuedItem {
  from: string;
  payload: string;
  queuedAt: number;
}

export class Mailbox {
  private store = new Map<string, QueuedItem[]>();

  constructor() {
    // Periodic sweep — belt-and-braces on top of the lazy expiry in `drain`.
    setInterval(() => this.sweepExpired(), 5 * 60 * 1000).unref();
  }

  enqueue(to: string, from: string, payload: string) {
    const items = this.store.get(to) ?? [];
    if (items.length >= MAX_ITEMS_PER_RECIPIENT) {
      items.shift(); // drop oldest rather than grow unbounded
    }
    items.push({ from, payload, queuedAt: Date.now() });
    this.store.set(to, items);
  }

  /** Pull and permanently clear everything queued for a recipient. */
  drain(to: string): QueuedItem[] {
    const items = this.store.get(to) ?? [];
    this.store.delete(to);
    const now = Date.now();
    return items.filter((i) => now - i.queuedAt <= MAILBOX_TTL_MS);
  }

  private sweepExpired() {
    const now = Date.now();
    for (const [to, items] of this.store.entries()) {
      const fresh = items.filter((i) => now - i.queuedAt <= MAILBOX_TTL_MS);
      if (fresh.length === 0) this.store.delete(to);
      else this.store.set(to, fresh);
    }
  }
}
