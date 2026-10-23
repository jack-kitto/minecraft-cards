package io.github.jackkitto.cards;

import java.util.*;

public record Deck(List<Prompt> prompts, List<Card> answers) {
    public record Card(String id, String text) {}
    public record Prompt(String id, String text, int picks) {
        public String complete(List<Card> cards) {
            String[] parts = text.split("___", -1);
            StringBuilder result = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                result.append(i <= cards.size() ? cards.get(i - 1).text() : "___");
                result.append(parts[i]);
            }
            return result.toString();
        }
    }

    public Deck {
        prompts = List.copyOf(prompts);
        answers = List.copyOf(answers);
        if (prompts.isEmpty() || answers.isEmpty()) throw new IllegalArgumentException("A pack needs prompts and answers.");
        Set<String> ids = new HashSet<>();
        for (Prompt prompt : prompts) {
            validate(prompt.id(), prompt.text(), ids);
            if (prompt.picks() < 1 || prompt.picks() > 3) throw new IllegalArgumentException("Prompt " + prompt.id() + " must pick 1, 2, or 3 cards.");
            int blanks = (prompt.text().length() - prompt.text().replace("___", "").length()) / 3;
            if (blanks != prompt.picks()) throw new IllegalArgumentException("Prompt " + prompt.id() + " needs exactly " + prompt.picks() + " ___ blanks.");
        }
        for (Card answer : answers) validate(answer.id(), answer.text(), ids);
    }

    private static void validate(String id, String text, Set<String> ids) {
        if (id == null || !id.matches("[a-zA-Z0-9_.:-]{1,80}")) throw new IllegalArgumentException("Card IDs must be 1-80 letters, digits, dots, colons, underscores, or hyphens.");
        if (!ids.add(id)) throw new IllegalArgumentException("Duplicate card ID: " + id);
        if (text == null || text.isBlank() || text.length() > 360 || text.indexOf('\u00a7') >= 0
                || text.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("Card " + id + " needs 1-360 printable characters without formatting codes.");
        }
    }

    public void requireCapacity(int players, int handSize) {
        if (answers.size() < players * handSize) throw new IllegalArgumentException("Selected packs need at least " + players * handSize + " answers for " + players + " hands.");
    }
}
