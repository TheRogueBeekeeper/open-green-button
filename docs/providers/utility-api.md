# Utility API

## Utilities

| Utility | Proxy id | Notes |
| ------------- | ------------- | ------------- |
| Consumers Energy (Michigan) | `consumers_energy` | [consumers-energy-mi.md](../utilities/consumers-energy-mi.md) |
| El Paso Electric (New Mexico / Texas) | `elpaso` | [elpaso-nmtx.md](../utilities/elpaso-nmtx.md) |
| Eversource (Massachusetts) | `eversource` | [eversource-ma.md](../utilities/eversource-ma.md) |

Issues for these utilities are labeled [`provider:utility-api`](https://github.com/rocketraman/open-green-button/issues?q=label%3Aprovider%3Autility-api).

## Registration

While registering as `opengreenbutton@googlegroups.com` you will get the message:

> This user is not eligible to register as a third party because it is a limited user at another utility. If you have any questions, please contact support.

To register, use a "plus-address" to register a new utility supported by Utility API of the form `opengreenbutton+<utility_slug>@googlegroups.com`.

For example, Eversource was created with this user:

```
opengreenbutton+eversource@googlegroups.com
```

## Ongoing monitoring (required for data to keep flowing)

UtilityAPI collects a meter **once**, when the customer authorizes, and not again: "By default,
Meters are not set to refresh automatically". The Green Button feed then stays frozen at the
authorization date however often it is polled — every poll succeeds and returns nothing new
([#76](https://github.com/rocketraman/open-green-button/issues/76)). Nothing in the ESPI scope
string changes this; a meter has to be enrolled in *ongoing monitoring* through UtilityAPI's own
JSON API (`POST /api/v2/meters/ongoing-monitoring`), or by hand in the dashboard.

The proxy does the enrolment itself. Whenever a client polls one of these utilities it lists the
registration's meters and enrols any with monitoring off, at `daily` (see
`proxy/UtilityApiMonitoring.kt`). Meters already on another frequency are left as they are.

This needs the registration's **API token** — a different credential from the OAuth client
id/secret. Each registration (one per utility, see below) has its own, under
*Settings → API Settings → API Tokens* in its UtilityAPI dashboard. Set it as a Fly secret:

```sh
fly secrets set OPENGB_UTILITY_ELPASO_UTILITYAPI_APITOKEN="..."
```

and likewise `OPENGB_UTILITY_CONSUMERS_ENERGY_UTILITYAPI_APITOKEN` and
`OPENGB_UTILITY_EVERSOURCE_UTILITYAPI_APITOKEN`. Without the secret the feature is off for that
utility and its meters are never re-collected.

Two things to know:

- The first refresh happens one period after enrolment, so a newly enrolled meter shows new data
  the following day.
- UtilityAPI documents monitoring as collecting "from the date you initiate monitoring onwards". A
  meter that sat unmonitored for a while may therefore keep a gap between its authorization-time
  collection and its enrolment; that gap is filled by a one-off historical collection
  (`POST /api/v2/meters/historical-collection`, or the dashboard), which the proxy does not do.
