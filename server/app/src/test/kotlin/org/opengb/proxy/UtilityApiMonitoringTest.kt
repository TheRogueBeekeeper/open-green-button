package org.opengb.proxy

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.opengb.http.UtilityHttpClients
import org.opengb.observability.captureLogs
import org.opengb.utility.UtilityApiConfig
import org.opengb.utility.UtilityProfile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

private const val API = "https://utilityapi.mock"
private const val LIST_URL = "$API/api/v2/meters"
private const val ENROL_URL = "$API/api/v2/meters/ongoing-monitoring"

val UtilityApiMonitoringTest by testSuite {
  test("enrols the meters nobody is monitoring, and only those") {
    val api =
      FakeUtilityApi { request ->
        when (request.url.toString()) {
          LIST_URL ->
            json(
              """
              {"meters": [
                {"uid": "1", "is_archived": false, "ongoing_monitoring": {"frequency": "off"}},
                {"uid": "2", "is_archived": false, "ongoing_monitoring": {"frequency": "daily"}},
                {"uid": "3", "is_archived": false, "ongoing_monitoring": {"frequency": "monthly"}},
                {"uid": "4", "is_archived": true, "ongoing_monitoring": {"frequency": "off"}},
                {"uid": "5", "is_archived": false}
              ], "next": null}
              """,
            )
          else -> json("""{"frequency": "daily", "meters": ["1", "5"], "success": true}""")
        }
      }

    val logged = captureLogs("opengb.utilityapi") { runBlocking { api.monitoring().ensureMonitored(utility()) } }

    // Meter 3 is left alone: someone chose monthly for it, and rewriting a subscription can cost money.
    val enrol = api.requests.single { it.method == HttpMethod.Post }
    assert(enrol.url.toString() == ENROL_URL)
    assert((enrol.body as TextContent).text == """{"meters":["1","5"],"frequency":"daily"}""")
    assert(api.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer api_token_xyz" })
    assert(logged.single()["utilityapi.monitoring.meters_seen"] == "5")
    assert(logged.single()["utilityapi.monitoring.meters_enrolled"] == "2")
    // Meter uids identify a customer; counts are all the log may carry.
    assert(logged.single().keys.all { it == "utility.id" || it.startsWith("utilityapi.monitoring.meters_") })
  }

  test("follows the listing across pages") {
    val api =
      FakeUtilityApi { request ->
        when (request.url.toString()) {
          LIST_URL -> json("""{"meters": [{"uid": "1"}], "next": "$LIST_URL?after=1"}""")
          "$LIST_URL?after=1" -> json("""{"meters": [{"uid": "2"}], "next": null}""")
          else -> json("""{"meters": ["1", "2"]}""")
        }
      }
    api.monitoring().ensureMonitored(utility())
    val enrol = api.requests.single { it.method == HttpMethod.Post }
    assert((enrol.body as TextContent).text == """{"meters":["1","2"],"frequency":"daily"}""")
  }

  test("does not carry the API token to a next-page link on another host") {
    val api =
      FakeUtilityApi { request ->
        when (request.url.toString()) {
          LIST_URL -> json("""{"meters": [], "next": "https://elsewhere.example/api/v2/meters"}""")
          else -> error("unexpected request to ${request.url}")
        }
      }
    api.monitoring().ensureMonitored(utility())
    assert(api.requests.map { it.url.toString() } == listOf(LIST_URL))
  }

  test("a registration with every meter monitored costs one request") {
    val api =
      FakeUtilityApi {
        json("""{"meters": [{"uid": "1", "ongoing_monitoring": {"frequency": "daily"}}], "next": null}""")
      }
    api.monitoring().ensureMonitored(utility())
    assert(api.requests.map { it.method } == listOf(HttpMethod.Get))
  }

  test("a burst of polls sweeps once, and again only after the interval") {
    val clock = TestTimeSource()
    val api = FakeUtilityApi { json("""{"meters": [], "next": null}""") }
    val monitoring = UtilityApiMonitoring(api.clients, minInterval = 1.hours, timeSource = clock)

    repeat(3) { monitoring.ensureMonitored(utility()) }
    assert(api.requests.size == 1)

    clock += 59.minutes
    monitoring.ensureMonitored(utility())
    assert(api.requests.size == 1)

    clock += 1.minutes
    monitoring.ensureMonitored(utility())
    assert(api.requests.size == 2)
  }

  test("each utility is throttled on its own") {
    val api = FakeUtilityApi { json("""{"meters": [], "next": null}""") }
    val monitoring = api.monitoring()
    monitoring.ensureMonitored(utility("elpaso"))
    monitoring.ensureMonitored(utility("eversource"))
    assert(api.requests.size == 2)
  }

  test("a utility with no API token is left alone") {
    val api = FakeUtilityApi { error("no request expected") }
    val monitoring = api.monitoring()
    monitoring.ensureMonitored(utility().copy(utilityApi = null))
    monitoring.ensureMonitored(utility().copy(utilityApi = UtilityApiConfig()))
    assert(api.requests.isEmpty())
  }

  test("a failing API is logged, not thrown, and says nothing about the meters") {
    val api =
      FakeUtilityApi { request ->
        when (request.method) {
          HttpMethod.Get -> json("""{"meters": [{"uid": "2114727"}], "next": null}""")
          else -> respond("""{"error": "meter 2114727 cannot be monitored"}""", HttpStatusCode.PaymentRequired)
        }
      }
    val logged = captureLogs("opengb.utilityapi") { runBlocking { api.monitoring().ensureMonitored(utility()) } }
    assert(logged.single()["utilityapi.monitoring.error"] == "enrol meters: HTTP 402") { logged.toString() }
    assert(logged.none { event -> event.values.any { "2114727" in it } })
  }

  test("a batch of only revoked meters is not a failure") {
    val api =
      FakeUtilityApi { request ->
        when (request.method) {
          HttpMethod.Get -> json("""{"meters": [{"uid": "1"}], "next": null}""")
          else -> respond("""{"errors": [{"code": "expired_or_revoked"}]}""", HttpStatusCode.BadRequest)
        }
      }
    val logged = captureLogs("opengb.utilityapi") { runBlocking { api.monitoring().ensureMonitored(utility()) } }
    assert(logged.single()["utilityapi.monitoring.meters_enrolled"] == "0") { logged.toString() }
  }
}

private fun utility(id: String = "elpaso") =
  UtilityProfile(
    id = id,
    displayName = "Mock",
    authorizeUrl = "https://utility.mock/authorize",
    tokenUrl = "https://utility.mock/token",
    clientId = "client_id_xyz",
    clientSecret = Masked("client_secret_xyz"),
    utilityApi = UtilityApiConfig(apiToken = Masked("api_token_xyz"), apiBaseUrl = API),
  )

private class FakeUtilityApi(
  handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
  val requests = CopyOnWriteArrayList<HttpRequestData>()
  val clients =
    UtilityHttpClients.singleClient(
      HttpClient(MockEngine) {
        engine {
          addHandler { request ->
            requests += request
            handler(request)
          }
        }
      },
    )

  fun monitoring() = UtilityApiMonitoring(clients)
}

private fun MockRequestHandleScope.json(body: String): HttpResponseData =
  respond(body.trimIndent(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
