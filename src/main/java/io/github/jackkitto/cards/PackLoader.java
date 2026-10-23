package io.github.jackkitto.cards;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PackLoader {
    private PackLoader() {}

    public static Map<String, Deck> load(Path folder) throws IOException {
        Map<String, Deck> packs = new TreeMap<>();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.json$", "");
                if (!name.matches("[a-z0-9_-]+")) throw new IOException("Pack filename must use lowercase letters, digits, _ or -: " + file.getFileName());
                try {
                    if (Files.size(file) > 1_048_576) throw new IllegalArgumentException("Pack exceeds 1 MiB.");
                    try (JsonReader reader = new JsonReader(Files.newBufferedReader(file))) {
                        reader.setStrictness(Strictness.STRICT);
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing data.");
                        List<Deck.Prompt> prompts = new ArrayList<>();
                        List<Deck.Card> answers = new ArrayList<>();
                        for (JsonElement element : root.getAsJsonArray("prompts")) {
                            JsonObject card = element.getAsJsonObject();
                            JsonPrimitive picks = card.getAsJsonPrimitive("picks");
                            if (!picks.isNumber() || !picks.getAsString().matches("[123]")) throw new IllegalArgumentException("picks must be the integer 1, 2, or 3.");
                            prompts.add(new Deck.Prompt(string(card, "id"), string(card, "text"), picks.getAsInt()));
                        }
                        for (JsonElement element : root.getAsJsonArray("answers")) {
                            JsonObject card = element.getAsJsonObject();
                            answers.add(new Deck.Card(string(card, "id"), string(card, "text")));
                        }
                        packs.put(name, new Deck(prompts, answers));
                    }
                } catch (RuntimeException ex) {
                    // Keep private card text out of logs and exception messages from the JSON parser.
                    throw new IOException("Invalid pack " + file.getFileName() + ". Check IDs, printable text, ___ blanks, and picks 1-3.");
                }
            }
        }
        if (packs.isEmpty()) throw new IOException("No .json packs found in decks/.");
        return Collections.unmodifiableMap(packs);
    }

    private static String string(JsonObject object, String key) {
        JsonPrimitive value = object.getAsJsonPrimitive(key);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Missing string.");
        return value.getAsString();
    }

    public static Deck combine(Map<String, Deck> packs, List<String> names) {
        if (names.isEmpty() || new HashSet<>(names).size() != names.size()) throw new IllegalArgumentException("Choose one or more distinct packs.");
        List<Deck.Prompt> prompts = new ArrayList<>();
        List<Deck.Card> answers = new ArrayList<>();
        for (String name : names) {
            Deck pack = packs.get(name);
            if (pack == null) throw new IllegalArgumentException("Unknown pack: " + name);
            prompts.addAll(pack.prompts());
            answers.addAll(pack.answers());
        }
        return new Deck(prompts, answers);
    }
}
