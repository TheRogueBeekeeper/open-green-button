# Cost models

**Status:** design proposal — not implemented
**Spans:** this repo (catalog + wire format) and [open-green-button-homeassistant](https://github.com/rocketraman/open-green-button-homeassistant) (evaluation + parameter UI)

## Problem

Cost reaches the Energy dashboard by one of two paths today, both driven entirely by what the
utility feed happens to contain:

1. **Per-interval cost** — savagedata/Elexicon custodians itemize cost on each reading.
   `statistics._import_cost_from_readings` writes it straight through.
2. **Billing summaries** — Burlington and others publish cost only in ESPI `UsageSummary`
   blocks. `statistics._import_cost_summaries` distributes each period total over that
   period's recorded hours, splitting out TOU line items where present.

Path 2 has a structural gap: a `UsageSummary` appears only when a bill *closes*. Between
bills the Cost column sits flat for up to a month while usage keeps accruing, then jumps.
There is no way to answer "what has this month cost me so far" — arguably the main thing a
user wants from an energy dashboard.

Closing that gap needs a **rate model**: enough information to price a kWh at a given hour
without a bill to divide up. That information is not in the ESPI feed. It has to come from
somewhere else.

### Why the obvious approaches don't scale

A [fork of the HA integration](https://github.com/geophilips/open-green-button-homeassistant/pull/4)
implemented this for one utility by reverse-engineering the rate model out of the previous
bill: match `Block 1`/`Tier 1` line items, divide by an assumed Ontario allowance to recover
each tier's rate, amortize the leftover as a flat per-kWh residual. It works, and the
per-hour pricing engine in it is sound. What doesn't generalize is where the numbers come
from:

- The Ontario tier thresholds (20 kWh/day summer, 1000/30 winter) and the summer/winter
  boundary are hardcoded in Python, gated by `entry.data[CONF_UTILITY_ID] == "milton_hydro"`.
  Every additional utility is another branch in a generic module.
- Those thresholds and the regulated commodity rates are set by the OEB and change on a
  roughly semi-annual cycle. Encoding them client-side means an HA release — and a user
  upgrade — for every rate change.
- Line-item label matching (`"smr"`, `"win"`, `"block 1"`) is per-custodian text parsing
  standing in for what is really regulator-published data.
- Where a bill can't supply the rates, the fork falls back to a service call asking the user
  to hand-enter four floats.

We already have a smaller version of the same problem in the client: `tou.py` hardcodes the
Ontario IESO time-of-use schedule, with a module docstring that says as much and defers the
generalization to "when we onboard a utility on a different schedule." Since [HA #16][ha16]
it also derives the OEB's holiday-pricing days from rules — Easter for Good Friday,
positional Mondays, and the OEB's roll-forward for holidays that land on a weekend. That was
the right fix for shipped behaviour, and it is exactly the kind of jurisdiction data this
design moves out of the client.

Meanwhile the rates are almost entirely **not per-utility**. Ontario's Regulated Price Plan
is identical across Alectra, Burlington, Milton, NPEI, NT Power, and Oakville — six of the
utilities already in `utilities.conf`. Encoding it once, server-side, is the natural shape.

## Goals

- Price the open billing period so the Cost column tracks usage continuously.
- Keep jurisdiction data (rates, thresholds, TOU schedules, season boundaries) out of client
  code and out of per-utility branches.
- Update rates without shipping an HA release.
- Support the case where the server genuinely cannot know a value, by asking the user for it
  — without making that the default experience.
- Exact utility bills always win. An estimate is provisional and is replaced.

### Non-goals

- Predicting *future* usage. We price hours that have already been recorded.
- Being a billing system. This is an approximation, and
  [already is one](https://github.com/rocketraman/open-green-button-homeassistant) even on
  the exact-bill path — see the flat-per-kWh delivery distribution in
  `_cost_distribution_for_period`.
- Per-user state on the server. The stateless invariant holds: the catalog is global config,
  and every user-supplied parameter lives only in Home Assistant.

## Design

### One shape, two degrees of completeness

A **cost model** is a declarative description of how to price a kWh. The server publishes a
catalog of them. Every model has the same shape; they differ only in how much the server can
fill in:

- **Fully specified** — every value is known server-side (Ontario RPP Tiered: regulated
  rates and thresholds are public). `parameters` is empty. The client can price immediately,
  with no user involvement.
- **Parameterized** — the calculation and its structure are known, but some values are
  account- or customer-specific (a negotiated rate, a utility that publishes rates only on
  a paper bill, a US tariff that varies by territory). The server declares *what it needs*;
  the client collects it from the user and evaluates locally.

This is a spectrum, not two classes. The same schema covers both, so the client has one code
path and gaining server-side knowledge over time just moves parameters out of the form.

### The calculation is named, never transmitted

A model carries `calculation: "<id>"` naming an implementation the client already has,
plus a value map. The server never ships expressions, formulas, or anything evaluated.

This is deliberate:

- No arbitrary-expression evaluator in an HA custom component, i.e. no path from
  "server compromised" to "code execution in every user's Home Assistant."
- The client can validate a model against the calculation's expected schema and reject
  malformed input, rather than failing at evaluation time on someone's dashboard.
- New calculations are a client release, which is correct — a genuinely new *rate
  structure* is new code. New *rates* are config, which is the case we're optimizing.

Initial calculation registry:

| `calculation` | Prices by | Covers |
|---|---|---|
| `flat` | single rate per kWh | most US default tariffs |
| `tiered` | consumption blocks with per-period allowances | Ontario RPP Tiered, many LDCs |
| `time_of_use` | hour-of-day/day-of-week buckets | Ontario RPP TOU, Ontario ULO |

`time_of_use` subsumes `tou.py`: the IESO schedule — including its holiday-pricing days —
becomes catalog data rather than a hardcoded classifier, and both the estimator *and* the
existing exact-bill TOU distribution in `_cost_distribution_for_period` read the schedule from
the model. See [Holidays](#holidays) for how those days are carried.

### Delivery

`/proxy/usage` is a zero-copy XML passthrough (`ByteReadChannelContent` streaming the
upstream body) with side-channel data in response headers — rotated credentials already ride
that way. So there is no JSON envelope to fold a catalog into.

Instead, a sibling to the existing public `GET /utilities`:

```
GET /cost-models/{utilityId}
→ 200 { "utilityId": "...", "models": [ ... ] }   ETag: "sha256:..."
→ 304 (If-None-Match matched)
```

Unauthenticated, cacheable, no per-user state — same surface shape as `/utilities`. The
client fetches it on the same cadence as its usage poll and stores the ETag, so the steady
state is one 304 per day per entry.

The claim response keeps the initial handshake honest: it gains `costModelsAvailable: true`
so a fresh config flow knows to collect parameters before the first import, rather than
importing a month of usage with no cost and correcting later.

**Rejected:** embedding the catalog in the claim response only. Claims happen once, at
authorization; rates change twice a year. An entry authorized in March would price November
at March's rates forever.

### Wire format

```jsonc
{
  "utilityId": "milton_hydro",
  "models": [
    {
      "id": "ontario_rpp_tiered",
      "displayName": "Regulated Price Plan — Tiered",
      "description": "Ontario's default residential plan. A lower price for the first block of
                      electricity each month, a higher price above it.",
      "calculation": "tiered",
      "currency": "CAD",
      "timezone": "America/Toronto",
      "effectiveFrom": "2026-05-01",
      "effectiveTo": "2026-10-31",   // null = open-ended
      "seasons": [
        { "id": "summer", "from": "05-01", "to": "10-31" },
        { "id": "winter", "from": "11-01", "to": "04-30" }
      ],
      "constants": {
        "tiers": {
          // Illustrative values — the real ones come from the OEB rate order.
          "summer": [
            { "allowanceKwhPerDay": 20.0,     "ratePerKwh": 0.0993 },
            {                                  "ratePerKwh": 0.1166 }
          ],
          "winter": [
            { "allowanceKwhPerDay": 33.333,   "ratePerKwh": 0.0993 },
            {                                  "ratePerKwh": 0.1166 }
          ]
        }
      },
      "parameters": [
        {
          "id": "residualRatePerKwh",
          "displayName": "Delivery, regulatory charges and taxes",
          "description": "Everything on your bill that isn't the electricity itself,
                          averaged per kWh.",
          "type": "rate",
          "unit": "CAD/kWh",
          "min": -1, "max": 1,
          "required": true,
          "deriveFrom": "last_bill_residual"
        }
      ]
    }
  ]
}
```

Two fields carry most of the design's weight:

**`parameters[].deriveFrom`** names a client-side derivation to attempt *before* asking the
user. `last_bill_residual` computes `(bill total − priced commodity charges) / period kWh`
from the most recent `UsageSummary` — which is exactly the fork's residual inference, kept
because it's genuinely better than asking: delivery and regulatory charges are per-account,
and the bill is the authoritative source for them. The user is prompted only when no bill is
available, and a later bill silently supersedes what they typed.

This is what makes the two "cost model kinds" a continuum. A model with zero parameters is
fully specified. A model whose parameters are all derivable never bothers the user in the
common case. A model with a non-derivable parameter (`type: "choice"` for a tariff the
server can't infer) shows a form.

**`effectiveFrom` / `effectiveTo`** version rates against wall-clock time. The client picks
the model whose window contains the hour being priced, and refuses to price hours past the
newest window's end — one warning, then it waits, rather than silently extrapolating stale
rates forward. This generalizes the "stop at the predicted period end" bound the fork added,
and turns "the server hasn't been updated for the November rate change" into a visible,
bounded condition instead of quietly wrong money.

A `time_of_use` model has the same envelope; its `constants` carry a schedule instead of
tiers:

```jsonc
{
  "id": "ontario_rpp_tou",
  "displayName": "Regulated Price Plan — Time-of-Use",
  "calculation": "time_of_use",
  "currency": "CAD",
  "timezone": "America/Toronto",
  "effectiveFrom": "2026-11-01",
  "effectiveTo": "2027-04-30",
  "seasons": [
    { "id": "summer", "from": "05-01", "to": "10-31" },
    { "id": "winter", "from": "11-01", "to": "04-30" }
  ],
  "constants": {
    // Each day type lists contiguous local-time ranges covering 00:00–24:00 exactly.
    "schedule": {
      "summer": {
        "weekday": [
          { "from": "00:00", "to": "07:00", "bucket": "off_peak" },
          { "from": "07:00", "to": "11:00", "bucket": "mid_peak" },
          { "from": "11:00", "to": "17:00", "bucket": "on_peak" },
          { "from": "17:00", "to": "19:00", "bucket": "mid_peak" },
          { "from": "19:00", "to": "24:00", "bucket": "off_peak" }
        ],
        "nonWorkingDay": [
          { "from": "00:00", "to": "24:00", "bucket": "off_peak" }
        ]
      },
      "winter": { /* same shape: on_peak 07–11 and 17–19, mid_peak 11–17 */ }
    },
    // Observed dates that use the nonWorkingDay schedule. Inlined by the server from a
    // shared holiday calendar — see Holidays below.
    "holidays": ["2026-12-25", "2026-12-28", "2027-01-01", "2027-02-15", "2027-03-26"],
    // Illustrative values. Used only by the open-period estimator: the exact-bill
    // distribution takes per-bucket rates from the bill's own TOU line items, and reads
    // only `schedule` and `holidays` from the model.
    "rates": { "off_peak": 0.098, "mid_peak": 0.157, "on_peak": 0.203 }
  },
  "parameters": [ /* residualRatePerKwh, as in the tiered model */ ]
}
```

The client's evaluation is: weekend or listed holiday ⇒ `nonWorkingDay`, otherwise
`weekday`; then find the range containing the local hour. Nothing else about the calendar is
the client's business.

### Holidays

`tou.py` hardcodes "holiday ⇒ off-peak all day", so the obvious schema would be a set of
dates that are off-peak. That doesn't survive the next model. Under Ontario's Ultra-Low
Overnight plan a holiday is priced like a weekend — ultra-low 23:00–07:00, weekend off-peak
the rest of the day — not flat off-peak. So holidays select a **day type**, and each
schedule says what that day type costs. RPP TOU's `nonWorkingDay` is one off-peak range; ULO's
is two ranges with different buckets. The same holiday list serves both.

**The catalog carries observed dates, not rules.** The client gets the final list of
weekday dates that take holiday pricing — the OEB's own table, after its roll-forward — and
does no date arithmetic. The alternative, shipping rule ids (`"easter-2"`,
`"nth_weekday:2:mon:3"`, a roll-forward policy) for the client to expand, was rejected:

- It is a rule language, which is what [the calculation is named, never
  transmitted](#the-calculation-is-named-never-transmitted) exists to avoid. It would grow per
  jurisdiction: US tariffs observe different holidays, some roll a Sunday holiday to Monday
  and a Saturday one to Friday, and some don't roll at all. A date list expresses all of
  these without a new client release.
- Roll-forward edge cases are where the bugs are. The first revision of [HA #16][ha16]
  priced only one of Christmas and Boxing Day when both land on a weekend (2021, 2027,
  2032, …). Getting that right server-side, once, beats getting it right in every client
  version still installed.
- The regulator publishes dates, not rules. A list can be checked against the OEB table by
  eye; a rule expansion can only be checked by running it.

Server-side, calendars are shared per jurisdiction, like the models themselves, and models
refer to them by id:

```hocon
holidayCalendars {
  ontario_oeb {
    source = "https://www.oeb.ca/consumer-information-and-protection/electricity-rates/holiday-schedule-time-use-and-ultra-low"
    # Observed weekday dates, exactly as the OEB table lists them. Weekend holidays are
    # already covered by the weekend rule; list only the weekday they roll onto.
    dates = [
      "2026-01-01", "2026-02-16", "2026-04-03", "2026-05-18", "2026-07-01",
      "2026-08-03", "2026-09-07", "2026-10-12", "2026-12-25", "2026-12-28",
      # 2027 …
    ]
  }
}

models = [
  { id = "ontario_rpp_tou", calculation = "time_of_use", holidayCalendar = "ontario_oeb", … }
]
```

`GET /cost-models/{utilityId}` inlines the calendar's dates into each model's
`constants.holidays`, trimmed to that model's effective window. The wire format stays
self-contained, so the client needs no second fetch and no calendar cache.

`CostModelRegistry` validates calendars at boot alongside everything else:

- every date is a weekday, so a weekend date is rejected as a likely mis-transcription;
- the calendar covers every day of every model window that refers to it, through
  `effectiveTo`. A calendar that runs out before the rates do fails the deploy rather than
  quietly pricing next year's Canada Day as a weekday. For open-ended windows, coverage
  must reach the end of the current year;
- a server test expands the OEB rules (a Kotlin port of `ontario_holiday_pricing_dates`
  from [HA #16][ha16]) for each listed year and asserts the conf matches. It is a typo
  check on the data, not the source of truth: if the OEB ever departs from its own rule,
  the conf wins and the test gets an explicit exception.

Updating holidays is then the same as updating rates: add the year's dates when the OEB
publishes them, `fly deploy`.

### Parameter types

| `type` | Renders as | Extra fields |
|---|---|---|
| `rate` | number box | `unit`, `min`, `max`, `step` |
| `number` | number box | `unit`, `min`, `max`, `step` |
| `choice` | dropdown | `options: [{value, label}]` |
| `boolean` | toggle | — |

Deliberately small. Anything a rate model needs that these can't express is a signal that it
wants a new `calculation`, not a richer parameter language.

### Which model applies

`utilities.conf` lists the models applicable to a utility, in order. When more than one
applies (Ontario customers choose between Tiered, TOU, and ULO), the client asks once, in
the options flow, and stores the choice. That question is plain — "which rate plan are
you on?" — and answerable from the top of any bill.

The bill's own line items are a good *hint* for pre-selecting the answer (TOU line items ⇒
TOU plan, Block/Tier line items ⇒ Tiered), and are worth using to pre-fill the dropdown. But
the hint stays advisory: label parsing decides what to *suggest*, never what to apply.

## Server changes

```
server/app/src/main/resources/cost-models.conf   # new: the catalog
server/app/src/main/kotlin/org/opengb/cost/CostModel.kt        # new: data classes
server/app/src/main/kotlin/org/opengb/cost/CostModelRegistry.kt # new: load + validate at boot
server/app/src/main/kotlin/org/opengb/routes/CostModels.kt      # new: GET /cost-models/{id}
server/app/src/main/kotlin/org/opengb/utility/UtilityProfile.kt # + costModels: List<String>
server/app/src/main/kotlin/org/opengb/routes/Claim.kt           # + costModelsAvailable
```

`UtilityProfile` gains one field, following the `initialHistory` / `pollInterval` precedent
exactly — a per-utility knob with a safe default, surfaced to the client:

```kotlin
/**
 * Ids of cost models from cost-models.conf that apply to this utility's customers, most
 * common first. Empty (the default) ⇒ this utility has no rate model and the client prices
 * only from what the feed itself carries. Rates are regulated per jurisdiction, not per
 * utility, so several utilities normally share one id.
 */
val costModels: List<String> = emptyList(),
```

and in `utilities.conf`, for every Ontario LDC:

```hocon
costModels = ["ontario_rpp_tiered", "ontario_rpp_tou", "ontario_rpp_ulo"]
```

`CostModelRegistry` validates at boot, as `UtilityRegistry` already does for
`initialHistorySeconds` / `pollIntervalSeconds`: every id referenced by a utility exists,
every `calculation` is one the current API version defines, tier and season coverage has no
gaps or overlaps, every TOU day type covers 24 hours with no gaps or overlaps,
`effectiveFrom` windows are contiguous, and every referenced holiday calendar covers its
models' windows (see [Holidays](#holidays)). A bad rate order should fail the deploy, not
reach a dashboard.

Rate updates then become: edit `cost-models.conf`, `fly deploy`. Every client picks it up
within a poll.

## Client changes

```
custom_components/greenbutton/cost_models.py    # new: schema + calculation registry
custom_components/greenbutton/tou.py            # retire: schedule becomes model data
custom_components/greenbutton/config_flow.py    # + parameter step, + plan choice
custom_components/greenbutton/statistics.py     # + open-period pricing pass
custom_components/greenbutton/coordinator.py    # + catalog fetch/ETag alongside the poll
```

Storage: `entry.options[CONF_COST_MODEL] = {"model_id": ..., "parameters": {...}}`. Options,
not `entry.data` — the user owns these, and it keeps the mutation in the config flow instead
of in the statistics writer. (The fork writes estimator state into `entry.data` from
`statistics.py`, which only works because the integration deliberately registers no reload
listener; that invariant is load-bearing enough already.)

The cached catalog itself goes in `entry.data` next to the other server-supplied facts, with
its ETag, since it's server state rather than user state.

### Pricing the open period

Per usage point, on each poll, after usage rows are written:

1. Establish the period boundary — the end of the newest closed `UsageSummary`.
2. Select the model for the entry, then the rate window covering each hour.
3. Walk recorded FORWARD hours from the boundary, evaluating the calculation. `tiered` spends
   its allowance as it goes and splits the crossing hour; `time_of_use` classifies each hour
   against the schedule; `flat` multiplies.
4. Add the residual per kWh.
5. Write cumulative rows continuing from the cost sum at the boundary.
6. When a bill closes, rewrite exactly the hours it covers with the exact distribution, then
   re-open at the new boundary.

**Statistic id.** Provisional and exact rows share the cost statistic. A separate
`…_cost_forecast` series would be self-documenting, but HA's Energy dashboard attaches one
cost statistic per consumption statistic, so it would force the user to choose between "what
I've been billed" and "what this month is costing" — a worse deal than the honesty is worth.

Sharing the series means the rewrite path carries the risk, so two invariants are
non-negotiable, and both need direct tests:

- **The cumulative sum is carried forward in-process and never re-derived from a recorder
  query.** Baselining a rewrite on "the last row before this timestamp" collapses to zero
  whenever that lookback window is empty — non-contiguous bills, a zero-cost period, a usage
  gap at a period end. HA reads external statistics as sum deltas, so a collapsed baseline
  renders as a large negative cost bar and erases every prior bill from the dashboard. This
  is a live bug in the fork's estimator, and was a live bug in an earlier revision of it too;
  it is the single easiest thing to get wrong here.
- **A rewrite covers exactly the hours the bill covers**, and the following estimate resumes
  from the sum the rewrite ended on — never from the pre-rewrite resume point.

### Config flow

New optional step after authorization, and reachable later via **Configure**:

- If the utility has no models: nothing shown, behaviour unchanged from today.
- If it has one model with no undelivered parameters: nothing shown; pricing just works.
- If it has several: "Which rate plan are you on?", pre-selected from bill line items where
  they're unambiguous.
- Then a form generated from the selected model's `parameters`, omitting any the client
  derived, with `min`/`max` driving the selector and validation.

Skipping the step is always allowed and leaves cost on today's summary-only behaviour.

## Rollout

1. **Catalog plumbing, no behaviour change.** `cost-models.conf`, registry, endpoint,
   `UtilityProfile.costModels`; client fetches and caches, does nothing with it. Verifiable
   in isolation.
2. **`time_of_use` + retire `tou.py`.** Move the IESO schedule and the `ontario_oeb`
   holiday calendar into the catalog and switch `_cost_distribution_for_period` to read
   them. Pure refactor — existing exact-bill tests must pass unchanged, which is the proof
   the schema is expressive enough before anything depends on it. That includes the holiday
   cases from [HA #16][ha16], re-pointed at a model built from catalog data: the 2026 OEB
   table, Canada Day off-peak all day, the 2021/2027 Saturday–Sunday and 2022 Sunday–Monday
   Christmas/Boxing Day rolls, and Remembrance Day staying a weekday. Their client-side
   rule expansion moves to the server test described under [Holidays](#holidays).
3. **`tiered` + `flat` + open-period pricing**, Ontario RPP Tiered first, with the residual
   derived from the last bill and no user-facing parameters at all.
4. **Parameter collection** in the config flow, for models the server can't fully specify.
5. **`ontario_rpp_ulo`** and the plan-selection step.

Steps 1–3 deliver the user-visible win with zero new UI. Step 4 is what makes the design
general, and its cost is only justified once step 3 proves the pricing engine.

## Risks

| Risk | Mitigation |
|---|---|
| Wrong rates put wrong money on a dashboard | Boot-time catalog validation; plausibility gates at evaluation; exact bills always overwrite; provisional rows never extend past the model's `effectiveTo` |
| Server not updated for a rate change | `effectiveTo` bounds every model; the client stops pricing and warns once rather than extrapolating |
| Rewrite corrupts the cumulative series | The two invariants above, each with a direct regression test — this is the known sharp edge |
| Schema outgrows the parameter types | New rate *structures* are new `calculation` ids (a client release); resist making the parameter language expressive |
| Catalog fetch fails | Cached catalog with its ETag stays usable; a fetch failure never fails a poll |
| Holiday calendar not extended for a new year | Boot validation fails the deploy if a calendar ends before any model window that uses it; the rule-expansion test flags a mistyped or missing date |

## Open questions

1. **Plan detection vs. asking.** Is the bill-line-item hint reliable enough across Ontario
   custodians to default to it and skip the question when confident, or does the plan
   dropdown always show?
2. **Non-Ontario jurisdictions.** `flat` and `time_of_use` should cover most US default
   tariffs, but demand charges, tiered-within-TOU, and seasonal fixed charges do not fit.
   Worth surveying two or three US utilities before freezing the schema.
3. **Fixed monthly charges.** Currently folded into the per-kWh residual, which smears a
   fixed cost across variable usage and is wrong in a low-usage month. A `fixedPerDay`
   component in every calculation would fix it — worth doing in step 3 rather than later?
4. **Should the residual be re-derived per bill or averaged over several?** A single bill is
   noisy (a true-up, a rebate change); a median over the last three is steadier but slower to
   track a real change.
5. **API versioning.** A new `calculation` id is additive and safe for old clients (they
   ignore models they can't evaluate, provided the server marks the minimum API version per
   model). Does that go on the model, or does it bump `CURRENT_API_VERSION`?
6. **Ontario's Saturday–Sunday Christmas.** When Christmas is a Saturday and Boxing Day a
   Sunday (2021, 2027, …), our reading of the OEB wording gives two observed days, Monday
   and Tuesday. That reading is in [HA #16][ha16] but has not been checked against a
   published OEB table for such a year. With dates in the catalog, the answer is a data
   edit either way — but the 2027 entry should be confirmed against the OEB table before it
   ships.

[ha16]: https://github.com/rocketraman/open-green-button-homeassistant/pull/16
