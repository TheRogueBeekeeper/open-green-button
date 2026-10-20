package org.opengb.utility

import com.sksamuel.hoplite.Masked
import com.typesafe.config.ConfigFactory
import de.infix.testBalloon.framework.core.testSuite

val UtilityRegistryTest by testSuite {
  test("a brand id resolves to its parent profile") {
    val registry = UtilityRegistry(listOf(parentProfile()))
    assert(registry.require("brand_a") === registry.require("parent"))
    assert(registry["brand_b"]?.id == "parent")
  }

  test("display names come from the brand when looked up by brand id") {
    val registry = UtilityRegistry(listOf(parentProfile()))
    assert(registry.displayName("parent") == "Parent Co")
    assert(registry.displayName("brand_a") == "Brand A")
  }

  test("summary lists the parent and each brand, ordered by display name") {
    val registry = UtilityRegistry(listOf(parentProfile(), sampleProfile("other", "Another Utility")))
    assert(
      registry.summary() ==
        listOf(
          UtilitySummary("other", "Another Utility"),
          UtilitySummary("brand_a", "Brand A"),
          UtilitySummary("brand_b", "brand B"),
          UtilitySummary("parent", "Parent Co"),
        ),
    ) { registry.summary().toString() }
  }

  test("all() returns each profile once despite its brands") {
    assert(UtilityRegistry(listOf(parentProfile())).all().size == 1)
  }

  test("a brand id colliding with another utility's id is rejected") {
    try {
      UtilityRegistry(listOf(parentProfile(), sampleProfile("brand_a", "Clash")))
      assert(false) { "expected a duplicate id to fail registry construction" }
    } catch (e: IllegalArgumentException) {
      assert(e.message!!.contains("brand_a")) { e.message!! }
    }
  }

  test("brands decode from HOCON") {
    val config =
      loadHocon<UtilitiesConfig>(
        """
        utilities = [{
          id = "parent", displayName = "Parent Co", authorizeUrl = "a", tokenUrl = "t",
          clientId = "c", clientSecret = "s"
          brands = [{ id = "brand_a", displayName = "Brand A" }]
        }]
        """,
      )
    assert(config.utilities.single().brands == listOf(UtilityBrand("brand_a", "Brand A")))
  }

  // See UtilityQuirksConfigTest for why the packaged file is parsed as raw HOCON.
  test("the packaged utilities.conf lists the Fortis Ontario brands") {
    val fortis =
      ConfigFactory.parseResources("utilities.conf")
        .getConfigList("utilities")
        .single { it.getString("id") == "fortis_ontario" }
    val brandIds = fortis.getConfigList("brands").map { it.getString("id") }
    assert(
      brandIds == listOf("algoma_power", "canadian_niagara_power", "cornwall_electric", "eastern_ontario_power"),
    ) { brandIds.toString() }
  }
}

private fun parentProfile(): UtilityProfile =
  sampleProfile("parent", "Parent Co")
    .copy(brands = listOf(UtilityBrand("brand_a", "Brand A"), UtilityBrand("brand_b", "brand B")))

private fun sampleProfile(
  id: String,
  displayName: String,
): UtilityProfile =
  UtilityProfile(
    id = id,
    displayName = displayName,
    authorizeUrl = "https://example/oauth/authorize",
    tokenUrl = "https://example/oauth/token",
    clientId = "opengreenbutton",
    clientSecret = Masked("secret"),
  )
