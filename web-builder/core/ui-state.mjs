/** UI-only helpers. Never persist private inputs or identities here. */
export function newApplicationId() {
  // Encode 128 cryptographically random bits as letters, avoiding reserved words.
  const letters = Array.from(crypto.getRandomValues(new Uint8Array(16)),
    byte => String.fromCharCode(97 + (byte >> 4), 97 + (byte & 15))).join('');
  return 'com.personal.oal.user' + letters;
}
