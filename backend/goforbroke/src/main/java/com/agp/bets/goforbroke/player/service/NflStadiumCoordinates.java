package com.agp.bets.goforbroke.player.service;

import java.util.Map;

/**
 * Static home-stadium coordinates for every NFL team, keyed by {@code Team.abbreviation}. Feeds
 * {@link WeatherForecastClient} - stadium locations are effectively permanent (no team has
 * relocated recently enough to matter), so this is plain hardcoded reference data rather than
 * something synced from an API, matching how {@code to_espn_team_abbreviation} in {@code _common.R}
 * handles the similarly-static nflverse/ESPN abbreviation crosswalk.
 *
 * <p>Deliberately independent of {@code Team.venueCity}/{@code venueName} (ESPN-sourced): those
 * were found to be stale for the two LA teams (still showing their pre-2020 temporary stadiums,
 * not SoFi) while building this - a pre-existing sync gap, not something to propagate into weather
 * lookups.
 */
final class NflStadiumCoordinates {

  private NflStadiumCoordinates() {}

  record Coordinates(double latitude, double longitude) {}

  private static final Map<String, Coordinates> BY_ABBREVIATION =
      Map.ofEntries(
          Map.entry("ARI", new Coordinates(33.5276, -112.2626)),
          Map.entry("ATL", new Coordinates(33.7554, -84.4008)),
          Map.entry("BAL", new Coordinates(39.2780, -76.6227)),
          Map.entry("BUF", new Coordinates(42.7738, -78.7870)),
          Map.entry("CAR", new Coordinates(35.2258, -80.8528)),
          Map.entry("CHI", new Coordinates(41.8623, -87.6167)),
          Map.entry("CIN", new Coordinates(39.0955, -84.5160)),
          Map.entry("CLE", new Coordinates(41.5061, -81.6995)),
          Map.entry("DAL", new Coordinates(32.7473, -97.0945)),
          Map.entry("DEN", new Coordinates(39.7439, -105.0201)),
          Map.entry("DET", new Coordinates(42.3400, -83.0456)),
          Map.entry("GB", new Coordinates(44.5013, -88.0622)),
          Map.entry("HOU", new Coordinates(29.6847, -95.4107)),
          Map.entry("IND", new Coordinates(39.7601, -86.1639)),
          Map.entry("JAX", new Coordinates(30.3239, -81.6373)),
          Map.entry("KC", new Coordinates(39.0489, -94.4839)),
          Map.entry("LAC", new Coordinates(33.9535, -118.3392)),
          Map.entry("LAR", new Coordinates(33.9535, -118.3392)),
          Map.entry("LV", new Coordinates(36.0909, -115.1833)),
          Map.entry("MIA", new Coordinates(25.9580, -80.2389)),
          Map.entry("MIN", new Coordinates(44.9735, -93.2575)),
          Map.entry("NE", new Coordinates(42.0909, -71.2643)),
          Map.entry("NO", new Coordinates(29.9511, -90.0812)),
          Map.entry("NYG", new Coordinates(40.8135, -74.0745)),
          Map.entry("NYJ", new Coordinates(40.8135, -74.0745)),
          Map.entry("PHI", new Coordinates(39.9008, -75.1675)),
          Map.entry("PIT", new Coordinates(40.4468, -80.0158)),
          Map.entry("SEA", new Coordinates(47.5952, -122.3316)),
          Map.entry("SF", new Coordinates(37.4030, -121.9700)),
          Map.entry("TB", new Coordinates(27.9759, -82.5033)),
          Map.entry("TEN", new Coordinates(36.1665, -86.7713)),
          Map.entry("WSH", new Coordinates(38.9076, -76.8645)));

  static Coordinates forAbbreviation(String abbreviation) {
    if (abbreviation == null) {
      return null;
    }
    return BY_ABBREVIATION.get(abbreviation.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
