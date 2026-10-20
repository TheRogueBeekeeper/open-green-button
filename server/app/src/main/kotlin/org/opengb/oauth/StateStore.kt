package org.opengb.oauth

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Carries enough context across the OAuth round-trip that the `/callback` handler can resolve
 * which utility we're returning from and which Home Assistant config flow opened the request.
 */
data class PendingOAuth(
  val utilityId: String,
  /** Optional HA-side nonce passed through ?ha_nonce=... — purely informational, surfaced in logs. */
  val haNonce: String?,
  /** The name the customer picked — a brand's name when they connected via a brand id. */
  val displayName: String? = null,
  val createdAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * In-memory CSRF state for the OAuth dance. Single instance, swappable interface if we ever
 * horizontally scale and need Redis-backed shared state.
 */
class StateStore(
  ttl: Duration,
  maxEntries: Long = 10_000,
  private val random: SecureRandom = SecureRandom(),
) {
  private val cache: Cache<String, PendingOAuth> =
    Caffeine.newBuilder()
      .expireAfterWrite(ttl)
      .maximumSize(maxEntries)
      .build()

  fun create(pending: PendingOAuth): String {
    val state = randomToken()
    cache.put(state, pending)
    return state
  }

  /** Atomically removes and returns the pending state, or null if absent / expired. */
  fun consume(state: String): PendingOAuth? = cache.asMap().remove(state)

  fun size(): Long = cache.estimatedSize()

  private fun randomToken(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return URL_ENCODER.encodeToString(bytes)
  }

  companion object {
    private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
  }
}
