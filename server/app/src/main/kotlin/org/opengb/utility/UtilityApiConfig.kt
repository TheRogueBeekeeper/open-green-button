package org.opengb.utility

import com.sksamuel.hoplite.Masked

/**
 * Access to UtilityAPI's own JSON API for a utility whose Green Button service UtilityAPI hosts
 * (Consumers Energy, El Paso Electric, Eversource). Separate from the ESPI/OAuth credentials: this
 * is the registration's API token, issued in that registration's UtilityAPI dashboard settings.
 *
 * It exists for one job. UtilityAPI collects a meter once, at authorization, and never again
 * unless the third party enrols the meter in ongoing monitoring — "By default, Meters are not set
 * to refresh automatically". Nothing in the ESPI scope string turns that on, so without enrolment
 * the Green Button feed stays frozen at the authorization date however often it is polled
 * (open-green-button issues/76). See [org.opengb.proxy.UtilityApiMonitoring].
 *
 * A block with no [apiToken] is inert, so the feature is off wherever the secret isn't set.
 */
data class UtilityApiConfig(
  /** The registration's API token. Null/blank ⇒ meters are not enrolled for this utility. */
  val apiToken: Masked? = null,
  /** Origin of the JSON API. The same host for every UtilityAPI-hosted utility. */
  val apiBaseUrl: String = "https://utilityapi.com",
  /** How often UtilityAPI re-collects an enrolled meter: `daily`, `weekly` or `monthly`. */
  val monitoringFrequency: String = "daily",
) {
  /** True when this block carries a token to call the API with. */
  fun hasToken(): Boolean = !apiToken?.value.isNullOrBlank()
}
