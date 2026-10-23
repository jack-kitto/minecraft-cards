# Minecraft cards

Run one shared card table for 3-8 Java and Bedrock players on Paper 26.3 build 211 and Java 25. Java players use chest menus. Bedrock players use Floodgate forms. The plugin needs no client mod or resource pack.

The original sample deck has 30 prompts and 120 answers. The default match deals ten cards per player and ends at five points.

## Install the plugin

1. Copy `minecraft-cards-0.1.0.jar` into your Paper server's `plugins/` directory. For the companion Docker deployment, use the paths in the [Minecraft server guide](https://github.com/jack-kitto/minecraft-server#configuration-layout).
2. Restart the server when players can leave.
3. Run `cards check` in the server console. Expect `Packs valid: 1. Selected deck: 30 prompts, 120 answers.`
4. Join as an operator. Look at a dedicated solid block within six blocks and run `/cards table`. The plugin uses that block without replacing it. Avoid a chest or another block you still need to open normally.

The first start creates `plugins/MinecraftCards/config.yml` and `plugins/MinecraftCards/decks/minecraft.json`. Keep runtime configuration, private packs, and table coordinates outside Git. For shared configuration managed through the infrastructure repository, follow its configuration synchronization rules and use private substitutions for coordinates.

Floodgate is optional for Java-only play. Bedrock forms require Floodgate, with Geyser providing the Bedrock connection. DreamDisplays is independent of this plugin.

## Play a match

1. Right-click the table, or run `/cards`.
2. Select **Join table**. The first player becomes the host. Have at least three players join.
3. As the host, open **Host controls**. Choose packs and a winning score, then select **Start match**.
4. On your turn to answer, open a card to read its complete text. Select **Pick this card**. For two-card and three-card prompts, choose in the order the blanks appear.
5. Select **Preview / Submit**. Read the completed answer, then select **Submit answer**. Use **Undo last pick** to change a selection before submitting.
6. As the judge, wait for submissions. Select **Reveal next answer** until every entry is visible. Open an answer, select **Choose answer**, and confirm the point.

The winner's name appears after judging. The next round starts after eight seconds. The judge rotates, and used cards refill. A final scoreboard stays visible when someone reaches the winning score.

Closing a menu keeps your picks. Open the table again to continue. Java players can hover over the books at the top to read the table text. Every answer also has a separate reading screen, including on Bedrock. A click on an old round refreshes the menu without applying that action.

Public chat and the table menu show prompts, submission counts, revealed answers, and scores. Players near the table also receive these public updates. Spectators receive no hands. Answer authors remain hidden until a winner is chosen.

## Handle an interrupted match

- Use **Pause** to freeze round and reconnect timers. Select **Resume** to continue.
- Use **Skip round** when a round needs to be abandoned. No point is awarded.
- Use **End match** to return to the lobby. Final scores remain until the next start.
- Let reconnecting players return within 120 seconds to keep their seat, hand, and score. A departed judge causes a new round. With fewer than three online players, the match waits at a boundary and resumes when enough players return or join.
- Use `/cards host` as an administrator to take control if the host cannot continue.

A submission timeout judges the answers received so far. With no answers, the game starts another round. A judge timeout skips the round without a point. New players receive a hand at the next boundary. Explicitly leaving releases the hand and score. Restarting the server ends the match.

## Add a pack

1. Put a UTF-8 `.json` file in `plugins/MinecraftCards/decks/`. Use a filename containing lowercase letters, digits, underscores, or hyphens.
2. Follow the [deck format and validation rules](docs/reference.md#deck-format). Use IDs unique across every pack you might enable together.
3. End the current match, then run `/cards reload` as an administrator.
4. Select the pack in **Host controls**, or run `/cards packs minecraft,your_pack`.
5. Run `/cards check` before starting.

Pack selection and the winning score apply to the next match and remain in memory until restart. Edit `packs` and `winning-score` in the private configuration to change restart defaults. The selected packs need at least `max-players * hand-size` answer cards. Store private source packs under `local/decks/`, which Git ignores, then copy them to the runtime deck directory. Keep private cards out of `src/main/resources/`.

## Build from source

Install Java 25 and Maven 3.9.11 or newer, then run from this directory:

```sh
mvn package
```

With Homebrew on Apple Silicon, use `JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn package` to select Java 25 if it is not your default JDK.

The build runs the rule and pack tests and writes `target/minecraft-cards-0.1.0.jar`. Paper and Floodgate remain server-provided dependencies. Nothing is shaded into the plugin.

See the [command and configuration reference](docs/reference.md), [validation record](docs/validation.md), and [disposable server probe instructions](integration/README.md).

Before deploying for a group, complete the client checks in the validation record. Controller navigation, actual Bedrock form rendering, and optional world display rendering still require players.
