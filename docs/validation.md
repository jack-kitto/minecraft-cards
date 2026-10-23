# Validation record

Built and checked locally on 23 October 2026 using Java 25 on macOS ARM64. The production server and world were not accessed or restarted.

## Automated rule and pack checks

`mvn package` passes 22 tests with no failures or skipped tests. The tests cover:

- Ordered two-card and three-card answers, removing a pick, undo, preview, submission locking, and hand refill.
- Judge rotation, all-reveal-before-choice, judge-only actions, one-time scoring, a winning score, and a fresh match reset.
- Anonymous public entries, hidden submissions, and entry order independent of submission timing.
- Old-round actions, duplicate submissions, and duplicate reveal actions.
- New players at boundaries, explicit departures, a departing judge, reconnect grace, too few players, and pause/resume.
- Submission and judging timeouts, including rounds with no submissions.
- Forty rounds with only enough answers to fill the hands. No duplicate card is dealt while another player holds it or submits it.
- Original sample deck capacity, strict JSON, malformed packs, duplicate IDs within and across packs, unsupported or fractional answer counts, literal answer substitution, and text limits.

## Disposable Paper checks

The test server uses Paper 26.3 build 211, Java 25, loopback address `127.0.0.1`, port `25585`, and a disposable world under the build workspace. It has no production world, player list, permissions database, or connection secrets.

Pinned Paper server SHA-256:

```text
f3a5953094696578193b38fa2831e86db254dfbf4817b1ac6cfca55a2d18ca94
```

The initial startup alongside Floodgate 2.2.5 build 141 enabled MinecraftCards successfully. `version MinecraftCards`, `cards check`, `cards status`, `cards reload`, and `cards help` returned the expected output. `cards start` from console returned the player-only message.

The separate test plugin in `integration/` exercises actual Paper inventory events with synthetic player objects. It checks menu creation, cancellation of shift clicks, number keys, drop keys, double clicks, offhand swaps, middle clicks, drags, and drops. It also checks selection persistence, duplicate callback rejection, stale-round refresh, stale-page callbacks, and that the menu never accesses the player's survival inventory.

The probe does not send network packets to a Minecraft client. Its result establishes server event behavior, not visual rendering or device usability. The probe is not included in the release JAR.

| Server check | Result |
| --- | --- |
| Paper 26.3 build 211 with Floodgate 2.2.5 build 141 | Pass, plugin enabled and menu probe completed. |
| Paper 26.3 build 211 without Floodgate | Pass, plugin enabled and menu probe completed. |
| Native form construction with Floodgate installed | Pass, including the adapter's explicit missing-player rejection. |
| Real Bedrock form delivery and rendering | Requires players. |
| Java rendering and controller navigation | Requires players. |

The Java-only test verifies that optional Floodgate classes are isolated from the Java menu. The native adapter checks player presence explicitly because [Floodgate's implementation can return success for an absent player](https://github.com/GeyserMC/Floodgate/blob/master/core/src/main/java/org/geysermc/floodgate/api/SimpleFloodgateApi.java).

## Checks that require players

Before using this with the group, complete one three-player match with a Java client and a Bedrock client through the existing Geyser and Floodgate deployment:

- On Java with Controlify and the 8BitDo controller, open the table, read card text, choose three ordered cards, undo, preview, and submit without keyboard input after opening.
- On Bedrock, verify form buttons, wrapping, scrolling, the full reading screen for a 360-character card, the three-card preview, closing, and reopening. Confirm that each response opens the next form reliably.
- Compare the prompt and every revealed entry across both clients. Check that spectators cannot view hands and that entries reveal no author before judging.
- Try shift clicks, hotbar swaps, dragging, dropping, and closing while holding survival items. Confirm those items and counts remain unchanged.
- Disconnect and reconnect an answerer and the judge. Verify the restored hand, round change, waiting state, and host controls.
- Finish a match and verify its scoreboard. Start another match with different packs and a different score.
- If enabling `board-display`, test the floating text through Geyser first, including long prompts and revealed entries. Public chat and menus remain the supported fallback with this option disabled.

No real Java client, Bedrock client, controller, Geyser connection, or world display was exercised during this build. Server restart recovery and multiple tables are outside this version's scope.
