package org.opengb.routes

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.opengb.AppDeps
import org.opengb.appModule
import org.opengb.buildAppDeps
import org.opengb.config.AppConfig
import org.opengb.config.CryptoConfig
import org.opengb.config.LandingConfig
import org.opengb.config.ServerConfig
import org.opengb.config.StateConfig
import org.opengb.oauth.PendingOAuth
import org.opengb.utility.UtilityBrand
import org.opengb.utility.UtilityProfile
import java.util.Base64

/**
 * A brand id (see [UtilityProfile.brands]) shows the brand's name to the customer, but the OAuth
 * round-trip runs under the parent id the custodian registered.
 */
val ConnectBrandTest by testSuite {
  test("the scope screen names the brand and continues under the brand id") {
    runWithBrand {
      val body = client.get("/connect/brand_a/scope") { parameter("ha_nonce", "n1") }.bodyAsText()
      assert("Share your Brand A data" in body) { body }
      assert("Parent Co" !in body) { body }
      assert("http://test.local/connect/brand_a/start?ha_nonce=n1" in body) { body }
    }
  }

  test("start redirects with the parent's callback and remembers the brand name") {
    runWithBrand { deps ->
      val client = createClient { followRedirects = false }
      val resp = client.get("/connect/brand_a/start")
      assert(resp.status == HttpStatusCode.Found) { "got ${resp.status}" }
      val location = resp.headers[HttpHeaders.Location]!!
      assert("redirect_uri=http%3A%2F%2Ftest.local%2Fconnect%2Fparent%2Fcallback" in location) { location }
      val state = Regex("[?&]state=([^&]+)").find(location)!!.groupValues[1]
      val pending = deps.stateStore.consume(state)!!
      assert(pending.utilityId == "parent") { pending.toString() }
      assert(pending.displayName == "Brand A") { pending.toString() }
    }
  }

  test("the callback names the brand the customer picked") {
    runWithBrand { deps ->
      val state =
        deps.stateStore.create(PendingOAuth(utilityId = "parent", haNonce = null, displayName = "Brand A"))
      val body =
        client.get("/connect/parent/callback") {
          parameter("error", "server_error")
          parameter("error_description", "Down for maintenance")
          parameter("state", state)
        }.bodyAsText()
      assert("Message from Brand A:" in body) { body }
    }
  }

  test("the landing page lists the brand with a brand-id connect link") {
    runWithBrand {
      val body = client.get("/").bodyAsText()
      assert("Brand A" in body) { body }
      assert("http://test.local/connect/brand_a/scope" in body) { body }
    }
  }
}

private fun runWithBrand(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(AppDeps) -> Unit) {
  val utility =
    UtilityProfile(
      id = "parent",
      displayName = "Parent Co",
      brands = listOf(UtilityBrand("brand_a", "Brand A")),
      authorizeUrl = "https://utility.mock/authorize",
      tokenUrl = "https://utility.mock/token",
      clientId = "client_id_xyz",
      clientSecret = Masked("client_secret_xyz"),
      defaultScope = "FB=1;IntervalDuration=900",
    )
  val config =
    AppConfig(
      server = ServerConfig(publicBaseUrl = "http://test.local"),
      crypto =
        CryptoConfig(
          aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })),
          hmacPepperBase64 =
            Masked(Base64.getEncoder().encodeToString(ByteArray(32) { ((it + 1) * 11).toByte() })),
        ),
      state = StateConfig(),
      landing = LandingConfig(),
      utilities = listOf(utility),
    )
  val deps = buildAppDeps(config)
  runBlocking {
    testApplication {
      application { appModule(deps) }
      block(deps)
    }
  }
}
