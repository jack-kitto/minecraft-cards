package io.github.jackkitto.cards;

import java.util.*;
import java.util.function.LongSupplier;
import static io.github.jackkitto.cards.Deck.*;

/** All methods are called on the server thread. Views never expose unrevealed authors. */
public final class Game {
    public enum Phase { LOBBY, WAITING, SUBMIT, REVEAL, RESULTS, FINISHED }
    public record Rules(int minPlayers, int maxPlayers, int handSize, int winningScore,
                        int submissionSeconds, int judgeSeconds, int reconnectSeconds, int resultSeconds) {
        public Rules {
            if (minPlayers < 3 || maxPlayers < minPlayers || maxPlayers > 8 || handSize < 3 || handSize > 18
                    || winningScore < 1 || winningScore > 100 || submissionSeconds < 10 || judgeSeconds < 10
                    || reconnectSeconds < 10 || resultSeconds < 2 || submissionSeconds > 3600
                    || judgeSeconds > 3600 || reconnectSeconds > 3600 || resultSeconds > 60) {
                throw new IllegalArgumentException("Invalid rules. Players 3-8, hand 3-18, goal 1-100, timers 10-3600s, results 2-60s.");
            }
        }
    }
    public record Standing(UUID id, String name, int score, boolean online, boolean waiting) {}
    public record PublicEntry(int number, String text) {}
    public record Board(Phase phase, boolean paused, long round, String prompt, int picks, String judge,
                        int submitted, int expected, List<PublicEntry> entries, String result,
                        List<Standing> scores, UUID host, int goal, long secondsLeft) {}
    public record Hand(List<Card> cards, List<String> selectedIds, String preview, boolean submitted, boolean canSelect) {}
    private static final class Seat {
        final UUID id;
        String name;
        final List<Card> hand = new ArrayList<>();
        final List<Card> selected = new ArrayList<>();
        int score;
        boolean online = true;
        long expires;
        Seat(UUID id, String name) { this.id = id; this.name = name; }
    }
    private record Entry(UUID author, List<Card> cards) {}

    private final Rules rules;
    private final LongSupplier clock;
    private final Random random;
    private final LinkedHashMap<UUID, Seat> seats = new LinkedHashMap<>();
    private final List<Card> draw = new ArrayList<>(), discards = new ArrayList<>();
    private final List<Prompt> prompts = new ArrayList<>(), promptDiscards = new ArrayList<>();
    private final Map<UUID, List<Card>> submissions = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<UUID> roundPlayers = new ArrayList<>();
    private UUID host, judge, previousJudge;
    private Phase phase = Phase.LOBBY;
    private Prompt prompt;
    private long round, deadline, pauseAt, version;
    private int revealed, goal;
    private boolean paused;
    private String result = "Join the table to play.";

    public Game(Rules rules, LongSupplier clock, Random random) {
        this.rules = rules;
        this.clock = clock;
        this.random = random;
        goal = rules.winningScore();
    }
    public Phase phase() { return phase; }
    public long round() { return round; }
    public long version() { return version; }
    public UUID host() { return host; }
    public UUID judge() { return judge; }
    public boolean contains(UUID id) { return seats.containsKey(id); }
    public boolean isPaused() { return paused; }
    private void changed() { version++; }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private Seat seat(UUID id) {
        Seat seat = seats.get(id);
        require(seat != null && seat.online, "Join the table first.");
        return seat;
    }
    private void action(long expectedRound, Phase expectedPhase) {
        require(expectedRound == round && phase == expectedPhase, "The round changed. Reopen the table.");
        require(!paused, "The host paused the game.");
    }

    public void join(UUID id, String name) {
        require(!seats.containsKey(id), "You already joined.");
        require(seats.size() < rules.maxPlayers(), "The table is full.");
        seats.put(id, new Seat(id, name));
        if (host == null) host = id;
        changed();
        if (phase == Phase.WAITING && !paused && onlineCount() >= rules.minPlayers()) nextRound();
    }

    public void connected(UUID id, String name) {
        Seat seat = seats.get(id);
        if (seat == null) return;
        if (!seat.online && clock.getAsLong() >= seat.expires && !paused) {
            remove(id);
            return;
        }
        seat.online = true;
        seat.name = name;
        seat.expires = 0;
        if (host == null || !seats.get(host).online) host = id;
        changed();
        if (phase == Phase.WAITING && !paused && onlineCount() >= rules.minPlayers()) nextRound();
    }

    public void disconnected(UUID id) {
        Seat seat = seats.get(id);
        if (seat == null) return;
        seat.online = false;
        seat.expires = (paused ? pauseAt : clock.getAsLong()) + rules.reconnectSeconds() * 1000L;
        transferHost();
        changed();
        if (active() && (id.equals(judge) || onlineRoundCount() < rules.minPlayers())) abandonRound("A player disconnected. Starting at the next boundary.");
    }

    public void leave(UUID id) { require(seats.containsKey(id), "You have not joined."); remove(id); }

    private void remove(UUID id) {
        Seat removed = seats.remove(id);
        if (removed == null) return;
        discards.addAll(removed.hand);
        List<Card> submitted = submissions.remove(id);
        if (submitted != null) discards.addAll(submitted);
        boolean wasPlaying = roundPlayers.remove(id);
        transferHost();
        changed();
        if (active() && wasPlaying) {
            if (id.equals(judge) || phase == Phase.REVEAL || onlineRoundCount() < rules.minPlayers()) abandonRound("A player left. Starting at the next boundary.");
            else maybeReveal();
        }
    }

    private void transferHost() {
        if (host != null && seats.containsKey(host) && seats.get(host).online) return;
        host = seats.values().stream().filter(s -> s.online).map(s -> s.id).findFirst().orElse(null);
    }

    public void takeHost(UUID id) { seat(id); host = id; changed(); }

    public void start(Deck deck, int winningScore) {
        require(phase == Phase.LOBBY || phase == Phase.FINISHED, "End the current match first.");
        require(onlineCount() >= rules.minPlayers(), "At least " + rules.minPlayers() + " online players must join.");
        require(winningScore >= 1 && winningScore <= 100, "Winning score must be 1-100.");
        deck.requireCapacity(rules.maxPlayers(), rules.handSize());
        cleanRound();
        draw.clear(); discards.clear(); prompts.clear(); promptDiscards.clear();
        draw.addAll(deck.answers()); prompts.addAll(deck.prompts());
        Collections.shuffle(draw, random); Collections.shuffle(prompts, random);
        seats.values().forEach(s -> { s.hand.clear(); s.selected.clear(); s.score = 0; });
        previousJudge = null;
        goal = winningScore;
        paused = false;
        nextRound();
    }

    public void toggle(UUID id, long expectedRound, String cardId) {
        action(expectedRound, Phase.SUBMIT);
        Seat seat = seat(id);
        require(roundPlayers.contains(id) && !id.equals(judge), "You join the next round, or you are the judge.");
        require(!submissions.containsKey(id), "Your answer is already submitted.");
        Card card = seat.hand.stream().filter(c -> c.id().equals(cardId)).findFirst().orElseThrow(() -> new IllegalArgumentException("That card is no longer in your hand."));
        if (!seat.selected.remove(card)) {
            require(seat.selected.size() < prompt.picks(), "Undo a card before choosing another.");
            seat.selected.add(card);
        }
        changed();
    }

    public void undo(UUID id, long expectedRound) {
        action(expectedRound, Phase.SUBMIT);
        Seat seat = seat(id);
        require(!submissions.containsKey(id), "Your answer is already submitted.");
        if (!seat.selected.isEmpty()) seat.selected.removeLast();
        changed();
    }

    public void submit(UUID id, long expectedRound) {
        action(expectedRound, Phase.SUBMIT);
        Seat seat = seat(id);
        require(roundPlayers.contains(id) && !id.equals(judge), "You cannot submit this round.");
        require(!submissions.containsKey(id), "Your answer is already submitted.");
        require(seat.selected.size() == prompt.picks(), "Choose " + prompt.picks() + " cards in order first.");
        submissions.put(id, List.copyOf(seat.selected));
        seat.hand.removeAll(seat.selected);
        seat.selected.clear();
        changed();
        maybeReveal();
    }

    private void maybeReveal() {
        if (phase == Phase.SUBMIT && submissions.size() >= roundPlayers.size() - 1) beginReveal();
    }

    private void beginReveal() {
        if (submissions.isEmpty()) { abandonRound("No answers arrived before the timeout."); return; }
        entries.clear();
        // Start in seating order, then shuffle. Hash iteration or submission timing never sets public order.
        for (UUID id : roundPlayers) if (submissions.containsKey(id)) entries.add(new Entry(id, submissions.get(id)));
        Collections.shuffle(entries, random);
        revealed = 0;
        phase = Phase.REVEAL;
        deadline = clock.getAsLong() + rules.judgeSeconds() * 1000L;
        result = "Judge: reveal each answer, then pick a winner.";
        changed();
    }

    public void reveal(UUID id, long expectedRound, int expectedRevealed) {
        action(expectedRound, Phase.REVEAL);
        require(id.equals(judge), "Only the judge can reveal answers.");
        seat(id);
        require(expectedRevealed == revealed && revealed < entries.size(), "That reveal is stale. Reopen the table.");
        revealed++;
        changed();
    }

    public void choose(UUID id, long expectedRound, int number) {
        action(expectedRound, Phase.REVEAL);
        require(id.equals(judge), "Only the judge can choose a winner.");
        seat(id);
        require(revealed == entries.size(), "Reveal every answer first.");
        require(number >= 1 && number <= revealed, "Choose a revealed answer.");
        Entry entry = entries.get(number - 1);
        Seat winner = seats.get(entry.author());
        require(winner != null, "The author left. Skip this round.");
        winner.score++;
        result = winner.name + " wins with answer " + number + "! " + prompt.complete(entry.cards());
        phase = winner.score >= goal ? Phase.FINISHED : Phase.RESULTS;
        deadline = clock.getAsLong() + rules.resultSeconds() * 1000L;
        changed();
    }

    public void skip() {
        require(phase != Phase.LOBBY && phase != Phase.FINISHED, "No match is running.");
        require(!paused, "Resume before skipping.");
        abandonRound("The host skipped the round.");
    }

    public void pause() {
        require(phase != Phase.LOBBY && phase != Phase.FINISHED, "No match is running.");
        if (paused) {
            long elapsed = clock.getAsLong() - pauseAt;
            deadline += elapsed;
            seats.values().stream().filter(s -> !s.online).forEach(s -> s.expires += elapsed);
            paused = false;
            if (phase == Phase.WAITING && onlineCount() >= rules.minPlayers()) nextRound();
        } else { paused = true; pauseAt = clock.getAsLong(); }
        changed();
    }

    public void end() {
        cleanRound();
        phase = Phase.LOBBY;
        paused = false;
        prompt = null;
        judge = null;
        round++;
        result = "Match ended. The final scores stay below until the next match.";
        changed();
    }

    public void tick() {
        if (paused) return;
        long now = clock.getAsLong();
        for (UUID id : seats.values().stream().filter(s -> !s.online && now >= s.expires).map(s -> s.id).toList()) remove(id);
        if (phase == Phase.WAITING && onlineCount() >= rules.minPlayers()) { nextRound(); return; }
        if (now < deadline) return;
        switch (phase) {
            case SUBMIT -> beginReveal();
            case REVEAL -> abandonRound("The judge timed out. No point awarded.");
            case RESULTS -> nextRound();
            default -> { }
        }
    }

    private boolean active() { return phase == Phase.SUBMIT || phase == Phase.REVEAL; }
    private int onlineCount() { return (int) seats.values().stream().filter(s -> s.online).count(); }
    private int onlineRoundCount() { return (int) roundPlayers.stream().filter(id -> seats.containsKey(id) && seats.get(id).online).count(); }

    private void abandonRound(String message) { nextRound(); result = message + "\n" + result; }

    private void cleanRound() {
        submissions.values().forEach(discards::addAll);
        submissions.clear(); entries.clear(); roundPlayers.clear(); revealed = 0;
        seats.values().forEach(s -> s.selected.clear());
        if (prompt != null) { promptDiscards.add(prompt); prompt = null; }
    }

    private void nextRound() {
        cleanRound();
        round++;
        changed();
        if (onlineCount() < rules.minPlayers()) {
            phase = Phase.WAITING;
            judge = null;
            result = "Waiting for " + rules.minPlayers() + " online players. Scores and hands are kept.";
            return;
        }
        List<UUID> order = new ArrayList<>(seats.keySet());
        int last = previousJudge == null ? -1 : order.indexOf(previousJudge);
        for (int step = 1; step <= order.size(); step++) {
            UUID candidate = order.get((last + step) % order.size());
            if (seats.get(candidate).online) { judge = candidate; break; }
        }
        previousJudge = judge;
        for (Seat seat : seats.values()) if (seat.online) {
            roundPlayers.add(seat.id);
            while (seat.hand.size() < rules.handSize()) seat.hand.add(drawCard());
        }
        if (prompts.isEmpty()) { prompts.addAll(promptDiscards); promptDiscards.clear(); Collections.shuffle(prompts, random); }
        prompt = prompts.removeLast();
        phase = Phase.SUBMIT;
        deadline = (paused ? pauseAt : clock.getAsLong()) + rules.submissionSeconds() * 1000L;
        result = "Choose " + prompt.picks() + " answer cards in order, preview, then Submit.";
    }

    private Card drawCard() {
        if (draw.isEmpty()) { draw.addAll(discards); discards.clear(); Collections.shuffle(draw, random); }
        if (draw.isEmpty()) throw new IllegalStateException("No answer cards available. Pack capacity invariant failed.");
        return draw.removeLast();
    }

    public Hand hand(UUID id) {
        Seat seat = seats.get(id);
        if (seat == null) return new Hand(List.of(), List.of(), "", false, false);
        List<Card> selected = submissions.getOrDefault(id, seat.selected);
        return new Hand(List.copyOf(seat.hand), selected.stream().map(Card::id).toList(),
                prompt == null ? "" : prompt.complete(selected), submissions.containsKey(id),
                seat.online && !paused && phase == Phase.SUBMIT && roundPlayers.contains(id) && !id.equals(judge) && !submissions.containsKey(id));
    }

    public Board board() {
        List<PublicEntry> publicEntries = new ArrayList<>();
        for (int i = 0; i < revealed; i++) publicEntries.add(new PublicEntry(i + 1, prompt.complete(entries.get(i).cards())));
        List<Standing> scores = seats.values().stream().map(s -> new Standing(s.id, s.name, s.score, s.online,
                active() && !roundPlayers.contains(s.id))).sorted(Comparator.comparingInt(Standing::score).reversed()).toList();
        long time = paused ? pauseAt : clock.getAsLong();
        return new Board(phase, paused, round, prompt == null ? "" : prompt.text(), prompt == null ? 0 : prompt.picks(),
                judge == null || !seats.containsKey(judge) ? "None" : seats.get(judge).name,
                submissions.size(), Math.max(0, roundPlayers.size() - 1), List.copyOf(publicEntries), result,
                scores, host, goal, Math.max(0, (deadline - time + 999) / 1000));
    }
}
