package io.github.jackkitto.cards;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GameTest {
    final AtomicLong time = new AtomicLong(100_000);
    final List<UUID> players = new ArrayList<>();
    Game game;

    Game.Rules rules(int max, int goal) { return new Game.Rules(3, max, 3, goal, 10, 10, 20, 2); }
    Deck deck(int picks, int answers) {
        return new Deck(List.of(new Deck.Prompt("p", String.join(" then ", Collections.nCopies(picks, "___")), picks)),
                java.util.stream.IntStream.range(0, answers).mapToObj(i -> new Deck.Card("a" + i, "Answer " + i)).toList());
    }
    void start(int count, int max, int picks, int answers, int goal) {
        game = new Game(rules(max, goal), time::get, new Random(32));
        for (int i = 0; i < count; i++) {
            UUID id = new UUID(0, i + 1); players.add(id); game.join(id, "Player" + i);
        }
        game.start(deck(picks, answers), goal);
    }
    void submit(UUID id) {
        if (id.equals(game.judge())) return;
        long round = game.round();
        for (Deck.Card card : game.hand(id).cards().subList(0, game.board().picks())) game.toggle(id, round, card.id());
        game.submit(id, round);
    }
    void allSubmit() {
        for (UUID id : players) if (game.contains(id) && game.hand(id).canSelect()) submit(id);
        assertEquals(Game.Phase.REVEAL, game.phase());
    }
    void allReveal() {
        while (game.board().entries().size() < game.board().submitted()) game.reveal(game.judge(), game.round(), game.board().entries().size());
    }
    void advance(long seconds) { time.addAndGet(seconds * 1000); game.tick(); }
    int totalPoints() { return game.board().scores().stream().mapToInt(Game.Standing::score).sum(); }

    @Test void orderedThreeCardAnswerIsPrivateUntilRevealedThenRefills() {
        start(3, 3, 3, 9, 5);
        UUID author = players.get(1);
        List<Deck.Card> cards = game.hand(author).cards();
        game.toggle(author, game.round(), cards.get(2).id());
        game.toggle(author, game.round(), cards.get(0).id());
        game.toggle(author, game.round(), cards.get(1).id());
        String answer = cards.get(2).text() + " then " + cards.get(0).text() + " then " + cards.get(1).text();
        assertEquals(answer, game.hand(author).preview());
        game.submit(author, game.round());
        assertTrue(game.board().entries().isEmpty());
        assertTrue(game.hand(author).cards().isEmpty());
        submit(players.get(2));
        assertTrue(game.board().entries().isEmpty());
        allReveal();
        assertTrue(game.board().entries().stream().anyMatch(e -> e.text().equals(answer)));
        assertEquals(Set.of("number", "text"), Arrays.stream(Game.PublicEntry.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        game.choose(game.judge(), game.round(), 1);
        assertEquals(1, totalPoints());
        advance(2);
        for (UUID id : players) assertEquals(3, game.hand(id).cards().size());
        assertEquals(players.get(1), game.judge());
    }

    @Test void twoCardPicksKeepOrderWhenRemovingAndUndoing() {
        start(3, 3, 2, 10, 5);
        UUID id = players.get(1);
        List<Deck.Card> cards = game.hand(id).cards();
        game.toggle(id, game.round(), cards.get(0).id());
        game.toggle(id, game.round(), cards.get(1).id());
        game.toggle(id, game.round(), cards.get(0).id());
        assertEquals(List.of(cards.get(1).id()), game.hand(id).selectedIds());
        assertThrows(IllegalArgumentException.class, () -> game.submit(id, game.round()));
        game.toggle(id, game.round(), cards.get(2).id());
        assertThrows(IllegalArgumentException.class, () -> game.toggle(id, game.round(), cards.get(0).id()));
        game.undo(id, game.round());
        assertEquals(List.of(cards.get(1).id()), game.hand(id).selectedIds());
    }

    @Test void onlyJudgeCanRevealOrScoreAndRepeatedActionsDoNotScoreTwice() {
        start(3, 3, 1, 9, 5);
        long round = game.round();
        allSubmit();
        assertThrows(IllegalArgumentException.class, () -> game.reveal(players.get(1), round, 0));
        assertThrows(IllegalArgumentException.class, () -> game.choose(game.judge(), round, 1));
        game.reveal(game.judge(), round, 0);
        assertThrows(IllegalArgumentException.class, () -> game.reveal(game.judge(), round, 0));
        assertEquals(1, game.board().entries().size());
        allReveal();
        assertThrows(IllegalArgumentException.class, () -> game.choose(players.get(1), round, 1));
        UUID judge = game.judge();
        game.choose(judge, round, 1);
        assertThrows(IllegalArgumentException.class, () -> game.choose(judge, round, 1));
        assertEquals(1, totalPoints());
    }

    @Test void duplicateSubmissionAndOldRoundCallbacksAreRejected() {
        start(3, 3, 1, 9, 5);
        UUID id = players.get(1);
        long round = game.round();
        submit(id);
        assertThrows(IllegalArgumentException.class, () -> game.submit(id, round));
        game.skip();
        assertThrows(IllegalArgumentException.class, () -> game.toggle(id, round, game.hand(id).cards().getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> game.submit(id, round));
    }

    @Test void exhaustionNeverDuplicatesCardsHeldOrInPlayAcrossManyRounds() {
        start(4, 4, 3, 12, 100);
        for (int round = 0; round < 40; round++) {
            Set<String> inHands = new HashSet<>();
            for (UUID id : players) for (Deck.Card card : game.hand(id).cards()) assertTrue(inHands.add(card.id()));
            assertEquals(12, inHands.size());
            allSubmit(); allReveal();
            game.choose(game.judge(), game.round(), 1);
            advance(2);
        }
        assertEquals(40, totalPoints());
    }

    @Test void joinsWaitUntilRoundBoundary() {
        start(3, 4, 1, 12, 5);
        UUID newcomer = new UUID(0, 40);
        game.join(newcomer, "Newcomer");
        assertTrue(game.hand(newcomer).cards().isEmpty());
        assertFalse(game.hand(newcomer).canSelect());
        assertEquals(2, game.board().expected());
        allSubmit(); allReveal();
        game.choose(game.judge(), game.round(), 1);
        advance(2);
        assertEquals(3, game.hand(newcomer).cards().size());
        assertEquals(3, game.board().expected());
    }

    @Test void reconnectRestoresHandAndSelectionsWithinGrace() {
        start(4, 4, 2, 12, 5);
        UUID id = players.get(1);
        Game.Hand before = game.hand(id);
        game.toggle(id, game.round(), before.cards().getFirst().id());
        long round = game.round();
        game.disconnected(id);
        advance(3);
        game.connected(id, "Back");
        assertEquals(round, game.round());
        assertEquals(before.cards(), game.hand(id).cards());
        assertEquals(1, game.hand(id).selectedIds().size());
    }

    @Test void absentSubmitterAndAbsentJudgeCannotStallPlay() {
        start(4, 4, 1, 12, 5);
        UUID offline = players.get(1);
        game.disconnected(offline);
        submit(players.get(2)); submit(players.get(3));
        advance(10);
        assertEquals(Game.Phase.REVEAL, game.phase());
        assertEquals(2, game.board().submitted());
        long previous = game.round();
        advance(10);
        assertTrue(game.round() > previous);
        assertEquals(0, totalPoints());
        assertFalse(game.contains(offline));
        assertEquals(Game.Phase.SUBMIT, game.phase());
    }

    @Test void departingJudgeRotatesAndTransfersHost() {
        start(4, 4, 1, 12, 5);
        UUID judge = game.judge();
        long before = game.round();
        game.disconnected(judge);
        assertNotEquals(judge, game.judge());
        assertNotEquals(judge, game.host());
        assertTrue(game.round() > before);
        assertEquals(Game.Phase.SUBMIT, game.phase());
    }

    @Test void tooFewPlayersWaitWithoutLosingScoresAndResumeAtBoundary() {
        start(3, 3, 1, 9, 5);
        allSubmit(); allReveal(); game.choose(game.judge(), game.round(), 1); advance(2);
        UUID id = players.get(2);
        List<Deck.Card> before = game.hand(id).cards();
        game.disconnected(id);
        assertEquals(Game.Phase.WAITING, game.phase());
        assertEquals(1, totalPoints());
        game.connected(id, "Returned");
        assertEquals(Game.Phase.SUBMIT, game.phase());
        assertEquals(before, game.hand(id).cards());
        assertEquals(1, totalPoints());
    }

    @Test void explicitLeaveRemovesSubmittedEntryAndReclaimsCards() {
        start(4, 4, 3, 12, 5);
        UUID leaving = players.get(1);
        submit(leaving);
        game.leave(leaving);
        assertEquals(0, game.board().submitted());
        submit(players.get(2)); submit(players.get(3));
        allReveal(); game.choose(game.judge(), game.round(), 1); advance(2);
        for (UUID id : players) if (game.contains(id)) assertEquals(3, game.hand(id).cards().size());
    }

    @Test void leavingDuringRevealCancelsTheRoundWithoutAPoint() {
        start(4, 4, 1, 12, 5);
        allSubmit(); allReveal();
        long round = game.round();
        game.leave(players.get(1));
        assertTrue(game.round() > round);
        assertEquals(Game.Phase.SUBMIT, game.phase());
        assertEquals(0, totalPoints());
    }

    @Test void pauseFreezesTimeoutsAndReconnectGrace() {
        start(4, 4, 1, 12, 5);
        game.disconnected(players.get(1));
        game.pause();
        long round = game.round();
        advance(100);
        assertEquals(round, game.round());
        assertTrue(game.contains(players.get(1)));
        assertThrows(IllegalArgumentException.class, () -> submit(players.get(2)));
        game.pause();
        advance(9);
        assertEquals(round, game.round());
        advance(11);
        assertFalse(game.contains(players.get(1)));
    }

    @Test void winEndsMatchAndRestartClearsPointsAndHands() {
        start(3, 3, 1, 9, 1);
        allSubmit(); allReveal(); game.choose(game.judge(), game.round(), 1);
        assertEquals(Game.Phase.FINISHED, game.phase());
        advance(100);
        assertEquals(Game.Phase.FINISHED, game.phase());
        game.start(deck(2, 9), 5);
        assertEquals(0, totalPoints());
        assertEquals(players.getFirst(), game.judge());
        for (UUID id : players) assertEquals(3, game.hand(id).cards().size());
    }

    @Test void noSubmissionsSkipAndSpectatorsCannotAct() {
        start(3, 3, 1, 9, 5);
        UUID spectator = UUID.randomUUID();
        assertFalse(game.hand(spectator).canSelect());
        assertThrows(IllegalArgumentException.class, () -> game.submit(spectator, game.round()));
        assertThrows(IllegalArgumentException.class, () -> game.submit(game.judge(), game.round()));
        long round = game.round(); advance(10);
        assertTrue(game.round() > round);
        assertEquals(0, totalPoints());
    }

    @Test void capacityAndRuleValidationHappenBeforeChangingGameState() {
        start(3, 4, 1, 12, 5);
        game.end();
        assertThrows(IllegalArgumentException.class, () -> game.start(deck(1, 11), 5));
        assertEquals(Game.Phase.LOBBY, game.phase());
        assertThrows(IllegalArgumentException.class, () -> new Game.Rules(2, 8, 10, 5, 120, 90, 120, 8));
    }

    @Test void submissionTimingDoesNotDeterminePublicEntryOrder() {
        start(4, 4, 1, 12, 5);
        allSubmit(); allReveal();
        List<Game.PublicEntry> first = game.board().entries();
        players.clear();
        start(4, 4, 1, 12, 5);
        for (int i = players.size() - 1; i >= 0; i--) if (!players.get(i).equals(game.judge())) submit(players.get(i));
        allReveal();
        assertEquals(first, game.board().entries());
    }
}
