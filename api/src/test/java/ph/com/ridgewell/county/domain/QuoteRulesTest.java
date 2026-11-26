package ph.com.ridgewell.county.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ph.com.ridgewell.county.domain.QuoteRules.Actor;
import ph.com.ridgewell.county.domain.QuoteRules.Status;

class QuoteRulesTest {
  private record Move(Status from, Status to, Actor actor) {}

  private static final Set<Move> LEGAL =
      Set.of(
          new Move(Status.DRAFT, Status.SENT, Actor.OWNER),
          new Move(Status.DRAFT, Status.CANCELLED, Actor.OWNER),
          new Move(Status.SENT, Status.CANCELLED, Actor.OWNER),
          new Move(Status.SENT, Status.ACCEPTED, Actor.RESELLER),
          new Move(Status.SENT, Status.DECLINED, Actor.RESELLER),
          new Move(Status.SENT, Status.EXPIRED, Actor.OWNER),
          new Move(Status.SENT, Status.EXPIRED, Actor.RESELLER));

  @Test
  void onlyTheLegalMovesAreAllowed() {
    for (Status from : Status.values())
      for (Status to : Status.values())
        for (Actor actor : Actor.values()) {
          var move = new Move(from, to, actor);
          if (LEGAL.contains(move)) assertEquals(to, QuoteRules.transition(from, to, actor));
          else
            assertThrows(
                IllegalArgumentException.class,
                () -> QuoteRules.transition(from, to, actor),
                move.toString());
        }
  }

  @Test
  void ownerCannotAcceptOrDeclineAndResellerCannotSendOrCancel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> QuoteRules.transition(Status.SENT, Status.ACCEPTED, Actor.OWNER));
    assertThrows(
        IllegalArgumentException.class,
        () -> QuoteRules.transition(Status.SENT, Status.DECLINED, Actor.OWNER));
    assertThrows(
        IllegalArgumentException.class,
        () -> QuoteRules.transition(Status.DRAFT, Status.SENT, Actor.RESELLER));
    assertThrows(
        IllegalArgumentException.class,
        () -> QuoteRules.transition(Status.SENT, Status.CANCELLED, Actor.RESELLER));
  }

  @Test
  void aSentQuoteExpiresTheDayAfterItsLastDay() {
    LocalDate last = LocalDate.of(2026, 12, 1);
    assertEquals(Status.SENT, QuoteRules.effective(Status.SENT, last, last));
    assertEquals(Status.EXPIRED, QuoteRules.effective(Status.SENT, last, last.plusDays(1)));
    for (Status other : EnumSet.complementOf(EnumSet.of(Status.SENT)))
      assertEquals(other, QuoteRules.effective(other, last, last.plusDays(30)));
  }

  @Test
  void nullsAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> QuoteRules.transition(null, Status.SENT, Actor.OWNER));
    assertThrows(
        IllegalArgumentException.class,
        () -> QuoteRules.effective(Status.SENT, null, LocalDate.now()));
  }
}
