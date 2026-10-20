package org.opengb.utility

import kotlinx.serialization.Serializable

/**
 * In-memory lookup over the configured utilities. Loaded once at startup from `utilities.conf`
 * via Hoplite; not mutable at runtime (server restart picks up changes).
 *
 * Lookups accept either a profile id or one of its [UtilityProfile.brands] ids; both resolve to the
 * profile, whose [UtilityProfile.id] is what the OAuth and notification flows use.
 */
class UtilityRegistry(profiles: List<UtilityProfile>) {
  private val listings: List<UtilitySummary> = utilityListings(profiles)

  private val byId: Map<String, UtilityProfile> =
    profiles.flatMap { profile -> (listOf(profile.id) + profile.brands.map { it.id }).map { it to profile } }.toMap()

  private val displayNames: Map<String, String> = listings.associate { it.id to it.displayName }

  init {
    require(displayNames.size == listings.size) {
      val dups = listings.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
      "Duplicate utility id in configuration: $dups"
    }
    // Fail fast at startup on a malformed window spec rather than 500ing later at claim time.
    profiles.forEach { profile ->
      runCatching {
        profile.initialHistorySeconds
        profile.pollIntervalSeconds
      }.onFailure { throw IllegalArgumentException("Utility '${profile.id}': ${it.message}", it) }
    }
  }

  operator fun get(id: String): UtilityProfile? = byId[id]

  fun require(id: String): UtilityProfile = byId[id] ?: throw UnknownUtilityException(id)

  fun all(): Collection<UtilityProfile> = byId.values.distinct()

  /** The customer-facing name for [id] — the brand's name when [id] is a brand id. */
  fun displayName(id: String): String = displayNames[id] ?: require(id).displayName

  fun summary(): List<UtilitySummary> = listings
}

/** Every listable entry — each profile plus each of its brands — ordered by display name. */
fun utilityListings(profiles: List<UtilityProfile>): List<UtilitySummary> =
  profiles
    .flatMap { profile ->
      listOf(UtilitySummary(profile.id, profile.displayName)) +
        profile.brands.map { UtilitySummary(it.id, it.displayName) }
    }.sortedBy { it.displayName.lowercase() }

@Serializable
data class UtilitySummary(val id: String, val displayName: String)

class UnknownUtilityException(val utilityId: String) :
  NoSuchElementException("No utility configured with id '$utilityId'")
