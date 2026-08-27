package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NflverseTeamAbbreviationsTest {

  @Test
  void crosswalksTheTwoFranchisesThatDiffer() {
    // The two real exceptions between nflverse's raw team codes and ESPN's abbreviations - see
    // _common.R's to_espn_team_abbreviation() for the R-side twin of this.
    assertEquals("LAR", NflverseTeamAbbreviations.toEspnAbbreviation("LA"));
    assertEquals("WSH", NflverseTeamAbbreviations.toEspnAbbreviation("WAS"));
  }

  @Test
  void leavesEveryOtherCodeUnchanged() {
    assertEquals("DAL", NflverseTeamAbbreviations.toEspnAbbreviation("DAL"));
    assertEquals("KC", NflverseTeamAbbreviations.toEspnAbbreviation("KC"));
  }
}
