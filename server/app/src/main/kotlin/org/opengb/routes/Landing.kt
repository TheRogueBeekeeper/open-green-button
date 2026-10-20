package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.strong
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import org.opengb.config.AppConfig
import org.opengb.utility.utilityListings

/**
 * Static brand assets (favicons, manifest, logo) are copied into the classpath at `static/` by
 * the app module's `processResources` block, sourced from `branding/web/` and `branding/`.
 * They're served at the root URL paths the favicon `<link>` tags expect:
 *   /favicon.ico, /favicon.svg, /favicon-32.png, /favicon-16.png, /icon-180.png,
 *   /icon-192.png, /icon-512.png, /site.webmanifest, /logo-horizontal.svg, /icon.svg
 */
fun Application.installLanding(config: AppConfig) {
  routing {
    staticResources("/", "static")
    get("/") {
      call.respondHtml { landingPage(config) }
    }
    // robots.txt — small enough to inline. Allow indexing of the public site, block /connect/*
    // (no point indexing OAuth redirect endpoints) and the /dashboard URLs (token-protected).
    get("/robots.txt") {
      call.respondText(
        ContentType.Text.Plain,
      ) {
        """
        User-agent: *
        Allow: /
        Disallow: /connect/
        Disallow: /claim/
        Disallow: /notify/
        Disallow: /dashboard/
        """.trimIndent()
      }
    }
  }
}

private fun HTML.landingPage(config: AppConfig) {
  head { pageHead(config) }
  body {
    siteHeader()
    hero()
    howItWorks()
    privacy()
    supportedUtilities(config)
    getStarted()
    support()
    siteFooter()
  }
}

private fun HEAD.pageHead(config: AppConfig) {
  title { +"Open Green Button — Utility energy data, in Home Assistant" }
  meta(charset = "utf-8")
  meta(name = "viewport", content = "width=device-width,initial-scale=1")
  meta(
    name = "description",
    content =
      "Stateless bridge between your utility's Green Button (NAESB ESPI) data feed " +
        "and your Home Assistant Energy dashboard. Open source.",
  )
  // Canonical URL — tells crawlers that the canonical hostname is the marketing one, even
  // if they reach this page via api.* or a www.* alias. The 301 redirect covers user-agents
  // that follow it; this covers crawlers and shared links that don't.
  config.server.canonicalHost?.takeIf { it.isNotBlank() }?.let { canonical ->
    link(rel = "canonical", href = "https://$canonical/")
  }
  // Favicons + app icons, served by staticResources("/", "static")
  link(rel = "icon", href = "/favicon.ico") { attributes["sizes"] = "any" }
  link(rel = "icon", href = "/favicon.svg", type = "image/svg+xml")
  link(rel = "icon", href = "/favicon-32.png", type = "image/png") { attributes["sizes"] = "32x32" }
  link(rel = "icon", href = "/favicon-16.png", type = "image/png") { attributes["sizes"] = "16x16" }
  link(rel = "apple-touch-icon", href = "/icon-180.png")
  link(rel = "manifest", href = "/site.webmanifest")
  meta(name = "theme-color", content = THEME_COLOR)
  // Montserrat (matches the wordmark in the logo SVG); system-ui falls back if blocked
  link(rel = "preconnect", href = "https://fonts.googleapis.com")
  link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
  link(
    rel = "stylesheet",
    href = "https://fonts.googleapis.com/css2?family=Montserrat:wght@400;500;600;700&display=swap",
  )
  style { unsafe { raw(STYLES) } }
}

private fun BODY.siteHeader() {
  div("site-header") {
    div("container") {
      a(href = "/", classes = "brand") {
        img(src = "/logo-horizontal.svg", alt = "Open Green Button") {
          attributes["height"] = "32"
        }
      }
      div("nav-links") {
        a(href = "#how-it-works") { +"How it works" }
        a(href = "#privacy") { +"Privacy" }
        a(href = "#get-started") { +"Get started" }
        a(href = "#support") { +"Support" }
        a(href = "https://github.com/rocketraman/open-green-button", classes = "nav-ghub") {
          +"GitHub"
        }
      }
    }
  }
}

private fun BODY.hero() {
  section("hero") {
    div("container") {
      h1 { +"Your utility's energy data, in Home Assistant" }
      p("lead") {
        +"Open Green Button bridges your power company's "
        a(href = "https://www.greenbuttondata.org/") { +"Green Button" }
        +" (NAESB ESPI) data feed into your Home Assistant Energy dashboard. "
        +"Stateless, open source, and your data never lives on our server. "
        +"Each utility generally requires signing up as a Green Button third-party data consumer. "
        +"With Open Green Button, the Home Assistant community can do this just once for each utility. "
      }
      div("cta-row") {
        a(href = "#get-started", classes = "btn btn-primary") { +"Get started" }
        a(
          href = "https://github.com/rocketraman/open-green-button",
          classes = "btn btn-secondary",
        ) { +"View on GitHub" }
      }
    }
  }
}

private fun BODY.howItWorks() {
  section("section-light") {
    attributes["id"] = "how-it-works"
    div("container") {
      h2 { +"How it works" }
      div("steps") {
        step("1", "Install the integration") {
          +"Add the Open Green Button custom component to your Home Assistant install via HACS."
        }
        step("2", "Authorize your utility") {
          +"Click through your utility's standard Green Button login. We never see your "
          +"utility password — the OAuth handshake stays between you and your power company."
        }
        step("3", "See your usage") {
          +"Hourly consumption flows into Home Assistant's Energy dashboard automatically, "
          +"keeping itself up to date in the background."
        }
      }
    }
  }
}

private fun FlowContent.step(
  num: String,
  heading: String,
  body: FlowContent.() -> Unit,
) {
  div("step") {
    div("step-num") { +num }
    h3 { +heading }
    p { body() }
  }
}

private fun BODY.privacy() {
  section("section-privacy") {
    attributes["id"] = "privacy"
    div("container") {
      h2 { +"Privacy is built in" }
      ul {
        li {
          strong { +"Your data lives only on your local Home Assistant server. " }
          +"Energy readings, billing data, and your utility's refresh token are stored on your "
          +"HA instance — never on our server."
        }
        li {
          strong { +"No accounts, no database. " }
          +"This site doesn't ask you to register. There's nothing to register for. "
          +"The server is a thin OAuth proxy that exists because utilities require a fixed public "
          +"callback URL."
        }
        li {
          strong { +"Open source under the MIT license. " }
          a(href = "https://github.com/rocketraman/open-green-button") {
            +"github.com/rocketraman/open-green-button"
          }
        }
      }
    }
  }
}

private fun BODY.supportedUtilities(config: AppConfig) {
  section("section-light") {
    div("container") {
      h2 { +"Supported utilities" }
      div("utilities") {
        for (utility in utilityListings(config.utilities)) {
          div("utility-card") {
            div("utility-name") { +utility.displayName }
            a(
              href = "${config.server.publicBaseUrl}/connect/${utility.id}/scope",
              classes = "btn btn-primary btn-small",
            ) { +"Connect" }
          }
        }
      }
      p("muted") {
        +"Want your utility added? "
        a(href = "https://github.com/rocketraman/open-green-button/issues") {
          +"Open an issue on GitHub"
        }
        +" — adding a new utility is a config change once they've approved the app."
      }
    }
  }
}

private fun BODY.getStarted() {
  section("section-getstarted") {
    attributes["id"] = "get-started"
    div("container") {
      h2 { +"Get started" }
      p { +"Three steps, one-time setup:" }
      ol("steps-list") {
        li {
          strong { +"Install via HACS. " }
          +"In Home Assistant, open HACS → ⋮ → "
          strong { +"Custom repositories" }
          +" and add "
          a(href = "https://github.com/rocketraman/open-green-button-homeassistant") {
            code { +"rocketraman/open-green-button-homeassistant" }
          }
          +" as an Integration. Then search "
          code { +"Open Green Button" }
          +" → Install. Restart Home Assistant when prompted."
        }
        li {
          strong { +"Add the integration. " }
          +"Settings → Devices & Services → Add Integration → "
          code { +"Open Green Button" }
          +". Pick your utility from the list."
        }
        li {
          strong { +"Authorize and paste back. " }
          +"Click the authorization link, sign in at your utility's Green Button page, "
          +"copy the claim code shown after consent, and paste it back into Home Assistant."
        }
      }
      p("muted") {
        +"That's it — your energy data appears in the Energy dashboard within a few minutes "
        +"and continues updating automatically."
      }
    }
  }
}

private fun BODY.support() {
  section("section-support") {
    attributes["id"] = "support"
    div("container") {
      h2 { +"Support the project" }
      p {
        +"Open Green Button is free to use. If it saves you time or you'd like to help keep "
        +"it maintained and hosted (there's a small Fly.io bill and ongoing time spent adding "
        +"new utilities and keeping up with Home Assistant changes), donations are welcome."
      }
      p {
        +"Suggested: "
        strong { +"\$5 / month" }
        +" — roughly enough to cover hosting plus a contribution toward maintenance time. "
        +"Anything above that funds new features and utility integrations and keeps me caffeinated."
      }
      div("cta-row") {
        a(
          href = "https://github.com/sponsors/rocketraman",
          classes = "btn btn-primary",
        ) { +"Sponsor on GitHub" }
        a(
          href = "https://www.buymeacoffee.com/rocketraman",
          classes = "btn btn-primary",
        ) { +"Buy Me a Coffee" }
      }
    }
  }
}

private fun BODY.siteFooter() {
  div("site-footer") {
    div("container") {
      div("footer-cols") {
        footerBrandColumn()
        footerProjectColumn()
        footerSupportColumn()
        footerContactColumn()
      }
      p("muted small footer-copy") {
        +"© 2026 Open Green Button Contributors. "
        +"\"Green Button\" is a registered trademark of the Green Button Alliance, "
        +"used here in reference to the open data standard."
      }
    }
  }
}

private fun FlowContent.footerBrandColumn() {
  div {
    strong { +"Open Green Button" }
    p("muted small") {
      +"An open-source bridge for utility energy data into Home Assistant."
    }
  }
}

private fun FlowContent.footerProjectColumn() {
  div {
    h3("footer-h") { +"Project" }
    ul("footer-list") {
      li {
        a(href = "https://github.com/rocketraman/open-green-button") { +"Server on GitHub" }
      }
      li {
        a(href = "https://github.com/rocketraman/open-green-button-homeassistant") {
          +"HA integration on GitHub"
        }
      }
      li {
        a(href = "https://github.com/rocketraman/open-green-button/issues") {
          +"Report an issue"
        }
      }
      li {
        a(href = "https://github.com/rocketraman/open-green-button/blob/master/LICENSE") {
          +"MIT License"
        }
      }
    }
  }
}

private fun FlowContent.footerSupportColumn() {
  div {
    h3("footer-h") { +"Support" }
    ul("footer-list") {
      li { a(href = "https://github.com/sponsors/rocketraman") { +"GitHub Sponsors" } }
      li { a(href = "https://www.buymeacoffee.com/rocketraman") { +"Buy Me a Coffee" } }
    }
  }
}

private fun FlowContent.footerContactColumn() {
  div {
    h3("footer-h") { +"Contact" }
    ul("footer-list") {
      li { a(href = "mailto:rocketraman@gmail.com") { +"rocketraman@gmail.com" } }
    }
  }
}

private const val BRAND_PRIMARY = "#1E5BFF"
private const val BRAND_CYAN = "#2DD4FF"
private const val INK = "#0B1B2B"
private const val THEME_COLOR = BRAND_PRIMARY

@Suppress("MaxLineLength")
private const val STYLES = """
:root {
  --primary: $BRAND_PRIMARY;
  --cyan: $BRAND_CYAN;
  --ink: $INK;
  --muted: #5b6a7a;
  --bg: #ffffff;
  --bg-soft: #f5f8fb;
  --bg-privacy: #eef4ff;
  --border: #e2e8f0;
  --gradient: linear-gradient(135deg, $BRAND_CYAN 0%, $BRAND_PRIMARY 100%);
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  font-family: 'Montserrat', system-ui, -apple-system, 'Segoe UI', sans-serif;
  font-weight: 400;
  color: var(--ink);
  background: var(--bg);
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
}
a { color: var(--primary); text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3 { font-weight: 700; line-height: 1.2; color: var(--ink); margin: 0 0 0.5rem; }
h1 { font-size: 2.5rem; }
h2 { font-size: 1.75rem; margin-top: 0; }
h3 { font-size: 1.1rem; }
p { margin: 0 0 1rem; }
code { background: #eef2f7; padding: 0.1rem 0.4rem; border-radius: 4px; font-size: 0.92em; font-family: ui-monospace, Menlo, Consolas, monospace; }
.container { max-width: 920px; margin: 0 auto; padding: 0 1.5rem; }
.muted { color: var(--muted); }
.small { font-size: 0.9rem; }
.site-header {
  border-bottom: 1px solid var(--border);
  background: var(--bg);
  position: sticky; top: 0; z-index: 10;
}
.site-header .container {
  display: flex; align-items: center; justify-content: space-between;
  padding-top: 0.75rem; padding-bottom: 0.75rem;
}
.brand { display: inline-flex; align-items: center; }
.brand img { display: block; height: 32px; }
.nav-links { display: flex; gap: 1.25rem; align-items: center; }
.nav-links a { color: var(--ink); font-weight: 500; font-size: 0.95rem; }
.nav-links a.nav-ghub {
  color: var(--bg);
  background: var(--ink);
  padding: 0.4rem 0.8rem;
  border-radius: 6px;
}
.hero {
  background: var(--bg-soft);
  padding: 4rem 0 3.5rem;
  border-bottom: 1px solid var(--border);
}
.hero h1 {
  background: var(--gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.lead { font-size: 1.15rem; color: var(--muted); margin-bottom: 1.75rem; max-width: 680px; }
.lead a { color: var(--primary); }
.cta-row { display: flex; gap: 0.75rem; flex-wrap: wrap; }
.btn {
  display: inline-block;
  padding: 0.7rem 1.4rem;
  border-radius: 8px;
  font-weight: 600;
  font-size: 0.98rem;
  text-decoration: none !important;
  transition: transform 0.05s ease-in-out, filter 0.15s ease-in-out;
  border: 1px solid transparent;
}
.btn:hover { filter: brightness(1.08); }
.btn:active { transform: translateY(1px); }
.btn-primary { background: var(--gradient); color: white; }
.btn-secondary { background: white; color: var(--ink); border-color: var(--border); }
.btn-small { padding: 0.45rem 0.95rem; font-size: 0.9rem; }
section { padding: 3.5rem 0; }
.section-light { background: var(--bg); }
.section-privacy { background: var(--bg-privacy); }
.section-getstarted { background: var(--bg-soft); border-top: 1px solid var(--border); }
.section-support { background: var(--bg); border-top: 1px solid var(--border); }
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
  margin-top: 1.5rem;
}
@media (max-width: 720px) { .steps { grid-template-columns: 1fr; } }
.step {
  background: var(--bg-soft);
  padding: 1.5rem;
  border-radius: 10px;
  border: 1px solid var(--border);
}
.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem; height: 2rem;
  border-radius: 999px;
  background: var(--gradient);
  color: white;
  font-weight: 700;
  margin-bottom: 0.5rem;
}
.section-privacy ul { padding-left: 1.25rem; }
.section-privacy li { margin-bottom: 0.85rem; }
.utilities {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 1rem;
  margin: 1.5rem 0;
}
.utility-card {
  background: white;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 1.25rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.utility-name { font-weight: 600; }
.steps-list { padding-left: 1.5rem; line-height: 1.7; }
.steps-list li { margin-bottom: 0.5rem; }
.site-footer {
  border-top: 1px solid var(--border);
  background: var(--ink);
  color: #c5d4e6;
  padding: 3rem 0 2rem;
}
.site-footer a { color: var(--cyan); }
.footer-cols {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 2rem;
  margin-bottom: 2rem;
}
@media (max-width: 900px) { .footer-cols { grid-template-columns: 1fr 1fr; gap: 1.5rem 2rem; } }
@media (max-width: 540px) { .footer-cols { grid-template-columns: 1fr; gap: 1.25rem; } }
.footer-h { color: white; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 0.5rem; }
.footer-list { list-style: none; padding: 0; margin: 0; }
.footer-list li { margin-bottom: 0.35rem; }
.footer-copy { border-top: 1px solid #1f3247; padding-top: 1.25rem; }
"""
