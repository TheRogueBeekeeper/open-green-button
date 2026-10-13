package org.opengb.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.message.StringMapMessage
import org.opengb.http.UtilityHttpClients
import org.opengb.utility.UtilityApiConfig
import org.opengb.utility.UtilityProfile
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val monitoringLog = logger("opengb.utilityapi")

/**
 * Keeps every meter of a UtilityAPI-hosted utility enrolled in ongoing monitoring, so UtilityAPI
 * goes on collecting it after the one collection it does at authorization. See [UtilityApiConfig]
 * for why that is needed at all.
 *
 * It works on the whole registration rather than on the caller's own meters: list the
 * registration's meters, enrol the ones nobody is monitoring. The proxy never parses a usage feed,
 * so it has no way to tell which meters belong to the request in hand — and a sweep also picks up
 * what a per-caller enrolment would miss: authorizations made before this existed, and a meter
 * added later to an account authorized with `meters-all`.
 *
 * Triggered by a client's poll, not by a timer: the server scales to zero and has no clock of its
 * own, and a poll is exactly when there is someone to keep data flowing for. [ensureMonitored] is
 * throttled per utility so a burst of polls costs one sweep. The throttle is in-process and resets
 * on every cold start, which is fine — a sweep that finds nothing to do is a single GET.
 *
 * Holds no per-user state, and nothing here may fail a poll: every outcome is logged and swallowed.
 */
class UtilityApiMonitoring(
  private val clients: UtilityHttpClients,
  private val minInterval: Duration = DEFAULT_MIN_INTERVAL,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val lastSweep = ConcurrentHashMap<String, TimeMark>()

  /**
   * Enrol [utility]'s unmonitored meters, unless it has no API token or was swept recently.
   * Never throws (other than cancellation).
   */
  @Suppress("TooGenericExceptionCaught") // best-effort by design: no failure here may reach the caller
  suspend fun ensureMonitored(utility: UtilityProfile) {
    val config = utility.utilityApi?.takeIf { it.hasToken() } ?: return
    if (!claimSweep(utility.id)) return
    try {
      val outcome = sweep(clients.forUtility(utility), config)
      monitoringLog.info(
        StringMapMessage().apply {
          put("utility.id", utility.id)
          put("utilityapi.monitoring.meters_seen", outcome.seen.toString())
          put("utilityapi.monitoring.meters_enrolled", outcome.enrolled.toString())
        },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Meter uids identify a customer, so the log carries the failure and no request detail.
      monitoringLog.warn(
        StringMapMessage().apply {
          put("utility.id", utility.id)
          put("utilityapi.monitoring.error", e.message ?: e.javaClass.simpleName)
        },
      )
    }
  }

  /** True if this call won the right to sweep [utilityId] now; the attempt counts even if it fails. */
  private fun claimSweep(utilityId: String): Boolean {
    var claimed = false
    lastSweep.compute(utilityId) { _, previous ->
      if (previous == null || previous.elapsedNow() >= minInterval) {
        claimed = true
        timeSource.markNow()
      } else {
        previous
      }
    }
    return claimed
  }

  private suspend fun sweep(
    http: HttpClient,
    config: UtilityApiConfig,
  ): SweepOutcome {
    val token = checkNotNull(config.apiToken).value
    val base = config.apiBaseUrl.trimEnd('/')
    val unmonitored = mutableListOf<String>()
    var seen = 0
    var url: String? = "$base/api/v2/meters"
    var pages = 0
    while (url != null && pages++ < MAX_PAGES) {
      val response = http.get(url) { authorize(token) }
      val page = JSON.decodeFromString(MeterPage.serializer(), response.requireSuccess("list meters"))
      seen += page.meters.size
      unmonitored += page.meters.filter { it.needsEnrolment() }.map { it.uid }
      // The token rides on whatever we fetch next, so only follow a page link that stays on the API.
      url = page.next?.takeIf { it.startsWith("$base/") }
    }
    var enrolled = 0
    for (batch in unmonitored.chunked(ENROL_BATCH_SIZE)) {
      val response =
        http.post("$base/api/v2/meters/ongoing-monitoring") {
          authorize(token)
          contentType(ContentType.Application.Json)
          setBody(JSON.encodeToString(EnrolRequest.serializer(), EnrolRequest(batch, config.monitoringFrequency)))
        }
      val body = response.bodyAsText()
      when {
        response.status.isSuccess() ->
          enrolled += JSON.decodeFromString(EnrolResponse.serializer(), body).meters.size
        // Revoked and expired meters are still listed, as unmonitored, and are skipped silently
        // when enrolled alongside live ones — but a batch made up ONLY of them is refused with
        // this. Nothing is wrong and nothing can be done, so it is not a failure.
        EXPIRED_OR_REVOKED in body -> Unit
        else -> error("enrol meters: HTTP ${response.status.value}")
      }
    }
    return SweepOutcome(seen, enrolled)
  }

  private data class SweepOutcome(
    val seen: Int,
    val enrolled: Int,
  )

  private companion object {
    val DEFAULT_MIN_INTERVAL = 1.hours

    // Bounds one sweep against a listing that never ends; far above any registration we have.
    const val MAX_PAGES = 50
    const val ENROL_BATCH_SIZE = 100
    const val EXPIRED_OR_REVOKED = "expired_or_revoked"
    val JSON = Json { ignoreUnknownKeys = true }
  }
}

private fun HttpRequestBuilder.authorize(token: String) {
  headers {
    append(HttpHeaders.Authorization, "Bearer $token")
    append(HttpHeaders.Accept, "application/json")
  }
}

/** The body, or a failure naming only the step and status — the body can name a customer's meter. */
private suspend fun HttpResponse.requireSuccess(step: String): String {
  check(status.isSuccess()) { "$step: HTTP ${status.value}" }
  return bodyAsText()
}

@Serializable
private data class MeterPage(
  val meters: List<Meter> = emptyList(),
  val next: String? = null,
)

@Serializable
private data class Meter(
  val uid: String,
  @SerialName("is_archived") val isArchived: Boolean = false,
  @SerialName("ongoing_monitoring") val ongoingMonitoring: OngoingMonitoring? = null,
) {
  /**
   * Only a meter with monitoring OFF is enrolled. One already on some other frequency was set
   * that way deliberately in the dashboard, and UtilityAPI warns that stopping and restarting a
   * monitoring subscription can incur charges — so an existing one is never rewritten.
   */
  fun needsEnrolment(): Boolean = !isArchived && ongoingMonitoring?.frequency.let { it.isNullOrBlank() || it == "off" }
}

@Serializable
private data class OngoingMonitoring(
  val frequency: String? = null,
)

@Serializable
private data class EnrolRequest(
  val meters: List<String>,
  val frequency: String,
)

@Serializable
private data class EnrolResponse(
  val meters: List<String> = emptyList(),
)
