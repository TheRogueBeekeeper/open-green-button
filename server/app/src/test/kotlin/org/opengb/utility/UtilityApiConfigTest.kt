@file:Suppress("MatchingDeclarationName") // file holds the test suite + a small Hoplite decode holder

package org.opengb.utility

import com.typesafe.config.ConfigFactory
import de.infix.testBalloon.framework.core.testSuite

data class UtilityApiHolder(val utilityApi: UtilityApiConfig? = null)

val UtilityApiConfigTest by testSuite {
  // What production sees wherever the Fly secret isn't set: `apiToken = ${?UNSET}` drops the key,
  // leaving an empty block. It has to decode, and it has to be inert.
  test("a block whose token env var is unset decodes to an inert config") {
    val config = loadHocon<UtilityApiHolder>("utilityApi: {}").utilityApi
    assert(config != null && !config.hasToken())
    assert(config?.monitoringFrequency == "daily")
  }

  test("a block with a token is live") {
    val config = loadHocon<UtilityApiHolder>("utilityApi: { apiToken: tok }").utilityApi
    assert(config?.hasToken() == true)
    assert(config?.apiToken?.value == "tok")
  }

  // Every UtilityAPI-hosted utility needs the block, or its feed freezes at the authorization date
  // (issues/76). Parsed raw, as in UtilityQuirksConfigTest — the env substitutions can't resolve here.
  test("the packaged utilities.conf gives every UtilityAPI-hosted utility a utilityApi block") {
    val utilities = ConfigFactory.parseResources("utilities.conf").getConfigList("utilities")
    for (id in listOf("consumers_energy", "elpaso", "eversource")) {
      val utility = utilities.single { it.getString("id") == id }
      assert(utility.hasPathOrNull("utilityApi")) { "$id has no utilityApi block" }
    }
  }
}
