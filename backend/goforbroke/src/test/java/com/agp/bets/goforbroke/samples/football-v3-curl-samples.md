# ESPN Football V3 cURL Samples

Copy any of these cURL commands into Postman’s import dialog.

## Athletes

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/athletes"
```

## League

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl"
```

## Season

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl/seasons/{season}"
```

## Jayden Daniels Lookup Flow

Use the list endpoint first, then filter the response for `Jayden Daniels`.

### 1) Pull the NFL athletes list

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes?limit=1000&page=1"
```

### 2) Open the athlete record once you have the ID

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes/{athleteId}"
```

### 3) Optional follow-up views

```bash
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes/{athleteId}/statisticslog"
curl --request GET "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes/{athleteId}/plays"
```

## Supported Query Parameters

ESPN does not publish one complete enum list for these parameters. In practice, they fall into a few buckets:

| Parameter | Likely values / shape | Notes |
| --- | --- | --- |
| `page`, `limit`, `weeks`, `advance`, `eventsback`, `eventsforward`, `eventsrange`, `period`, `season`, `eventId` | Integers or integer-like strings | Pagination and ID-style fields are usually numeric. For athlete lists, `limit=1000` is a practical starting point. |
| `_hoist`, `_help`, `_trace`, `_nocache` | Flags | Debug and response-shaping switches; behavior is endpoint-specific. |
| `pq`, `q`, `filter`, `seek` | Search text or expression strings | Usually free-form text, not fixed enums. |
| `lang`, `region`, `utcOffset`, `postalCode` | Locale codes, time offset, or postal code | Examples: `en`, `us`, `-05:00`, `90210`. |
| `dates` | Date strings or lists | Commonly `YYYYMMDD` or comma-separated date values. |
| `type`, `types`, `seasontypes`, `status`, `statuses`, `groups`, `provider`, `site`, `league.type`, `sort`, `position`, `source`, `competitions`, `teams`, `competitors`, `networks`, `guids`, `oldteams`, `newteams`, `tournaments` | Enum-like slugs or ID lists | Allowed values are endpoint-specific and usually come from related response data. For NFL, these should be interpreted in the NFL context only. |
| `calendar.type`, `calendar.groups`, `event.recurring`, `provider.priority`, `record.splits`, `record.seasontype`, `statistic.splits`, `statistic.seasontype`, `statistic.qualified`, `statistic.context`, `roster.positions`, `roster.athletes`, `team.athletes`, `powerindex.rundatetimekey`, `eventstates`, `eventresults`, `situation.play`, `played`, `available`, `active`, `homeAway`, `profile`, `opponent`, `athlete.position`, `award.type`, `notes.type`, `tidbit.type`, `bets.promotion`, `ids.sportware` | Endpoint-specific filters | These often behave like booleans, IDs, or comma-delimited filters depending on the route. |
| `split`, `splits` | Split selectors or split IDs | Route-specific; for QBR, split values are documented elsewhere in this repo. |

If you want the real allowed values, the most reliable path is:

1. Call the endpoint with a minimal query string.
2. Inspect the response for adjacent IDs, slugs, and filter fields.
3. Try one parameter at a time in Postman until ESPN returns either data or a validation-style error.

That usually gives us the practical value set faster than searching for published docs.
