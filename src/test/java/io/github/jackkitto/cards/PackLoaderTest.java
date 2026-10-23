package io.github.jackkitto.cards;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackLoaderTest {
    @TempDir Path folder;
    String pack(String picks, String answerId) {
        return "{\"prompts\":[{\"id\":\"p\",\"text\":\"Why ___?\",\"picks\":" + picks
                + "}],\"answers\":[{\"id\":\"" + answerId + "\",\"text\":\"A chicken.\"}]}";
    }
    @Test void originalPackHasEnoughAnswersForEightPlayers() throws IOException {
        Files.copy(Objects.requireNonNull(getClass().getResourceAsStream("/decks/minecraft.json")), folder.resolve("minecraft.json"));
        Deck deck = PackLoader.combine(PackLoader.load(folder), List.of("minecraft"));
        assertEquals(30, deck.prompts().size()); assertEquals(120, deck.answers().size());
        deck.requireCapacity(8, 10);
        assertEquals(Set.of(1, 2, 3), deck.prompts().stream().map(Deck.Prompt::picks).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void rejectsMalformedPacksDuplicateIdsUnsupportedCountsAndFractionalCounts() throws IOException {
        for (String invalid : List.of("{}", "not json", pack("1", "p"), pack("4", "a"), pack("1.5", "a"), pack("\"1\"", "a"), pack("1", "a") + " trailing")) {
            Files.writeString(folder.resolve("test.json"), invalid);
            IOException error = assertThrows(IOException.class, () -> PackLoader.load(folder));
            assertFalse(error.getMessage().contains("A chicken"));
        }
    }
    @Test void rejectsDuplicateIdsAcrossPacksAndUnknownOrRepeatedPackSelection() throws IOException {
        Files.writeString(folder.resolve("one.json"), pack("1", "a"));
        Files.writeString(folder.resolve("two.json"), pack("1", "a"));
        Map<String, Deck> packs = PackLoader.load(folder);
        assertThrows(IllegalArgumentException.class, () -> PackLoader.combine(packs, List.of("one", "two")));
        assertThrows(IllegalArgumentException.class, () -> PackLoader.combine(packs, List.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> PackLoader.combine(packs, List.of("one", "one")));
    }
    @Test void requiresMatchingBlanksAndPrintableBoundedText() {
        assertThrows(IllegalArgumentException.class, () -> new Deck(List.of(new Deck.Prompt("p", "___", 2)), List.of(new Deck.Card("a", "Answer"))));
        assertThrows(IllegalArgumentException.class, () -> new Deck(List.of(new Deck.Prompt("p", "___", 1)), List.of(new Deck.Card("a", "\u00a7csecret"))));
        assertThrows(IllegalArgumentException.class, () -> new Deck(List.of(new Deck.Prompt("p", "___", 1)), List.of(new Deck.Card("a", "x".repeat(361)))));
    }
    @Test void fillsCardsLiterallyEvenWithDollarSignsOrBackslashes() {
        Deck.Prompt prompt = new Deck.Prompt("p", "___ meets ___", 2);
        assertEquals("$5 meets C:\\blocks", prompt.complete(List.of(new Deck.Card("a", "$5"), new Deck.Card("b", "C:\\blocks"))));
        assertEquals("A ___ meets second", prompt.complete(List.of(new Deck.Card("a", "A ___"), new Deck.Card("b", "second"))));
    }
}
