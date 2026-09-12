/**
 * RAM-only prekey bulletin board. All material held here is PUBLIC key
 * data (plus a per-device registration id) — the corresponding private
 * keys are generated and stay on-device via libsignal. This module's
 * only job is what Signal's own prekey server does: let peer B publish
 * a batch of one-time prekeys + a signed prekey, and let peer A fetch
 * (and consume) one of them to start X3DH asynchronously, even while B
 * is offline.
 *
 * Nothing here is message content. Nothing here is written to disk.
 * A restart simply means every device re-uploads on next connect —
 * cheap, and matches the project's zero-persistence posture.
 */

import type { OneTimePreKeyDto, SignedPreKeyDto } from "./types";

interface DevicePreKeyState {
  registrationId: number;
  identityKey: string;
  signedPreKey: SignedPreKeyDto;
  oneTimePreKeys: OneTimePreKeyDto[]; // consumed FIFO as bundles are fetched
}

export class PreKeyStore {
  private byNumericId = new Map<string, DevicePreKeyState>();

  upload(
    numericId: string,
    registrationId: number,
    identityKey: string,
    signedPreKey: SignedPreKeyDto,
    oneTimePreKeys: OneTimePreKeyDto[],
  ): number {
    const existing = this.byNumericId.get(numericId);
    const merged = existing ? [...existing.oneTimePreKeys, ...oneTimePreKeys] : oneTimePreKeys;
    this.byNumericId.set(numericId, {
      registrationId,
      identityKey,
      signedPreKey, // always replace with the latest signed prekey
      oneTimePreKeys: merged,
    });
    return merged.length;
  }

  /** Consumes (removes) one one-time prekey, if any remain, and returns a full bundle. */
  consumeBundle(numericId: string): {
    registrationId: number;
    identityKey: string;
    signedPreKey: SignedPreKeyDto;
    oneTimePreKey?: OneTimePreKeyDto;
  } | undefined {
    const state = this.byNumericId.get(numericId);
    if (!state) return undefined;

    const oneTimePreKey = state.oneTimePreKeys.shift(); // FIFO consumption, matches Signal's server behavior
    return {
      registrationId: state.registrationId,
      identityKey: state.identityKey,
      signedPreKey: state.signedPreKey,
      oneTimePreKey,
    };
  }

  remove(numericId: string) {
    this.byNumericId.delete(numericId);
  }
}
