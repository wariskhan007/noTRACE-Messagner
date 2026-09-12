/**
 * Pure in-memory registry. No disk writes, no database, ever.
 *
 * Holds ONLY the minimal lookup mapping described in the plan:
 *   numericId  -> { publicKey, username?, socket, online }
 *   username   -> numericId  (case-insensitive index)
 *
 * On process restart this is empty — by design. Clients re-register
 * their existing numericId (it's derived from their own long-term
 * identity key, not assigned by the server) on next connect.
 */

import type WebSocket from "ws";

export interface RegisteredPeer {
  numericId: string;
  username?: string;
  publicKey: string;
  socket: WebSocket | null; // null when offline
  lastSeen: number;
}

export class Registry {
  private byNumericId = new Map<string, RegisteredPeer>();
  private byUsername = new Map<string, string>(); // lowercase username -> numericId

  register(numericId: string, publicKey: string, socket: WebSocket, username?: string):
    | { ok: true; peer: RegisteredPeer }
    | { ok: false; code: "ID_TAKEN" | "USERNAME_TAKEN" } {
    const existing = this.byNumericId.get(numericId);
    // Allow re-registration by the same identity (e.g. reconnect) — the
    // client proves ownership implicitly by presenting the same public key.
    if (existing && existing.publicKey !== publicKey) {
      return { ok: false, code: "ID_TAKEN" };
    }

    if (username) {
      const lower = username.toLowerCase();
      const holder = this.byUsername.get(lower);
      if (holder && holder !== numericId) {
        return { ok: false, code: "USERNAME_TAKEN" };
      }
      this.byUsername.set(lower, numericId);
    }

    const peer: RegisteredPeer = {
      numericId,
      username,
      publicKey,
      socket,
      lastSeen: Date.now(),
    };
    this.byNumericId.set(numericId, peer);
    return { ok: true, peer };
  }

  markSocket(numericId: string, socket: WebSocket | null) {
    const peer = this.byNumericId.get(numericId);
    if (peer) {
      peer.socket = socket;
      peer.lastSeen = Date.now();
    }
  }

  lookup(query: string): RegisteredPeer | undefined {
    const byId = this.byNumericId.get(query);
    if (byId) return byId;
    const idFromUsername = this.byUsername.get(query.toLowerCase());
    if (idFromUsername) return this.byNumericId.get(idFromUsername);
    return undefined;
  }

  get(numericId: string): RegisteredPeer | undefined {
    return this.byNumericId.get(numericId);
  }

  /** Remove a peer entirely (e.g. explicit account deletion — never on mere disconnect). */
  remove(numericId: string) {
    const peer = this.byNumericId.get(numericId);
    if (peer?.username) this.byUsername.delete(peer.username.toLowerCase());
    this.byNumericId.delete(numericId);
  }

  allOnline(): RegisteredPeer[] {
    return [...this.byNumericId.values()].filter((p) => p.socket !== null);
  }
}
