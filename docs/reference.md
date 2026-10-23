# Commands and configuration

## Commands

| Command | Access | Effect |
| --- | --- | --- |
| `/cards` | `cards.play` | Opens your hand, lobby, or public table view. |
| `/cards join` | `cards.play` | Joins now in the lobby, or queues for the next boundary during play. Creating a lobby also requires `cards.host`. |
| `/cards watch` | `cards.play` | Opens the public table and subscribes to updates until disconnect. |
| `/cards leave` | Joined player | Releases the hand and score. The player can continue watching. |
| `/cards status` | `cards.play`, or console | Prints public table state. |
| `/cards help` | `cards.play`, or console | Lists commands. |
| `/cards start` | Host, or administrator | Starts a match using the selected packs and score. |
| `/cards score <1-100>` | Host, or administrator | Sets the next match's winning score in the lobby or after a finished match. |
| `/cards packs` | `cards.play` | Lists available and selected packs. |
| `/cards packs <name,name>` | Host, or administrator | Replaces the next match's selected packs. Validates capacity before changing the selection. |
| `/cards pause` | Host, or administrator | Toggles pause. Round and reconnect timers freeze. |
| `/cards skip` | Host, or administrator | Abandons a running round without a point. Requires the match to be resumed. |
| `/cards end` | Host, or administrator | Ends the match and returns to the lobby. |
| `/cards host` | `cards.admin` | Joins if necessary and takes host control. A full table must have a free seat first. |
| `/cards table` | `cards.admin` | Uses the targeted solid block within six blocks as the table. Saves its location privately. |
| `/cards table clear` | `cards.admin` | Removes the configured table location. Command access remains available. |
| `/cards check` | `cards.admin`, or console | Validates pack files and capacity without changing the match. |
| `/cards reload` | `cards.admin`, or console | Reloads pack files between matches. Rules and table settings load on restart. |

Console commands omit the leading slash. Console can use `help`, `status`, `check`, and `reload`. Game control commands require a player.

## Permissions

| Permission | Default | Meaning |
| --- | --- | --- |
| `cards.play` | Everyone | Table menus, joining, and public status. |
| `cards.host` | Everyone | Create a lobby and control it while assigned host. |
| `cards.admin` | Operators | Table placement, pack validation and reload, host takeover, and all host controls. |

Host controls require both the assigned host role and `cards.host`. Administrators can control the match without holding the host role. Host status transfers to the first online seated player when its owner leaves or disconnects. If permissions have been restricted and the new host lacks `cards.host`, an administrator can take over.

## Configuration

The runtime file is `plugins/MinecraftCards/config.yml`. These are the shipped defaults:

| Key | Default | Range or meaning |
| --- | --- | --- |
| `min-players` | `3` | 3 through `max-players`. |
| `max-players` | `8` | `min-players` through 8, including seats held during reconnect grace. |
| `hand-size` | `10` | 3-18. The sample deck supports eight hands of up to 15 cards. |
| `winning-score` | `5` | 1-100. |
| `submission-seconds` | `120` | 10-3600. Starts when a prompt is drawn. |
| `judge-seconds` | `90` | 10-3600. Total time to reveal and choose after submissions close. |
| `reconnect-seconds` | `120` | 10-3600. Offline seats keep their remaining hand and score during this period. |
| `result-seconds` | `8` | 2-60. Time before the next round. |
| `packs` | `[minecraft]` | Filenames without `.json`. At least one distinct pack is required. |
| `table` | `{}` | `world` UUID and integer `x`, `y`, `z`. Set with `/cards table`. |
| `board-display` | `false` | Optional floating text above the block. Public chat and menus work without it. |
| `board-radius` | `16` | Radius for nearby public chat updates, clamped to 1-64 blocks. |

Configuration defaults load on server startup. Invalid rules or selected packs disable the plugin with a validation message. A missing table world leaves command access working. The plugin creates no database and does not persist matches or player hands.

The optional text display is temporary and is removed when the plugin disables. Geyser rendering has not been tested, so `board-display` remains disabled by default.

## Deck format

Each JSON file has `prompts` and `answers` arrays. This small example illustrates the format and has too few answers to start a match:

```json
{
  "prompts": [
    {"id": "my_pack.prompt.1", "text": "Combine ___ with ___.", "picks": 2}
  ],
  "answers": [
    {"id": "my_pack.answer.1", "text": "A chicken in a minecart."},
    {"id": "my_pack.answer.2", "text": "A suspicious bucket of lava."}
  ]
}
```

- IDs contain 1-80 letters, digits, dots, colons, underscores, or hyphens. IDs are unique across prompts, answers, and every selected pack.
- Text contains 1-360 printable characters. Control characters, newlines, and Minecraft formatting codes are rejected. Menus wrap long text.
- `picks` is an integer from 1 to 3. Each prompt has exactly that many `___` blanks.
- Both arrays contain at least one card. Files are at most 1 MiB and use strict JSON.
- The combined selection has at least `max-players * hand-size` answers, even when fewer players start.

Used answers go into a discard pile. An empty draw pile reshuffles discards while excluding held and submitted cards. Prompts reshuffle after the prompt pile empties. Pack parse errors identify the filename without copying private card text into logs.

The bundled Minecraft pack is original writing for this project. No commercial expansion or downloaded CAH deck is bundled.

## Round behavior

The phases are `lobby`, `submit`, `reveal`, `results`, and `finished`. `waiting` holds a running match at a boundary until enough players are online. Pause freezes timers and answer actions.

The judge rotates in seating order among online players. New players join at a boundary. Menus store selections in the game, so closing a menu does not change them. Submission locks the selected cards in order. All entries are shuffled from seating order, independent of arrival timing. Public entry records contain only a number and completed text.

A disconnected non-judge keeps the seat until grace expires. An active round can continue if at least the configured minimum number of its players remain online. A disconnected judge abandons the round immediately. Too few online players cause the match to wait. An explicit departure during reveals also abandons that round to prevent awarding a removed player.

The submission timer closes with the answers received so far. No submissions cause a new round. Judge timeout awards no point. Winner confirmation can award only once. Score and hand state are discarded on a server restart.

## API references

The build uses the [Paper project setup and build-specific API coordinates](https://docs.papermc.io/paper/dev/project-setup/), [custom inventory holders](https://docs.papermc.io/paper/dev/custom-inventory-holder/), [Floodgate API](https://geysermc.org/wiki/floodgate/api/), and [Cumulus forms](https://geysermc.org/wiki/geyser/forms/).
