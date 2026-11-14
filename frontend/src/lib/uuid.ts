/**
 * Generates a UUIDv4 compatible string. Falls back to Math.random() when
 * crypto.randomUUID is unavailable (non-secure contexts like plain HTTP
 * on a remote host).
 */
export function generateUuid(): string {
  if (typeof window.crypto !== 'undefined' && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  // Fallback UUIDv4 generator using Math.random (not cryptographically secure)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
