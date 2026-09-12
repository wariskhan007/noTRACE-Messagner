import { createHmac } from "crypto";

/**
 * Mints short-lived TURN credentials per coturn's `use-auth-secret` / REST
 * API convention (see ../../coturn/turnserver.conf.example, which sets
 * `static-auth-secret`). This lets the signaling server hand out temporary
 * TURN access without coturn maintaining its own account database, and
 * without the signaling server ever persisting a per-user TURN password.
 *
 * username = "<expiryEpochSeconds>:<label>"
 * password = base64(HMAC-SHA1(sharedSecret, username))
 */
export function mintTurnCredentials(
  label: string,
  sharedSecret: string,
  ttlSeconds = 3600,
): { username: string; password: string; ttlSeconds: number } {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
  const username = `${expiry}:${label}`;
  const password = createHmac("sha1", sharedSecret).update(username).digest("base64");
  return { username, password, ttlSeconds };
}
