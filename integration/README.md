# Run the disposable server menu probe

The probe creates synthetic players and drives inventory events inside Paper. It shuts the server down after writing its result. It does not test client rendering or controller input.

Use a separate scratch directory, Paper 26.3 build 211, and a fresh world. Bind the server to `127.0.0.1` on an unused port such as `25585`. Never point the probe at a live server directory or an existing world. The probe JAR is a test fixture and is excluded from the release JAR.

1. Build the project with Java 25 and `mvn package`.
2. Create a scratch server directory and put the pinned Paper JAR in it. Configure the scratch server and EULA acceptance before starting it.
3. Copy `target/minecraft-cards-0.1.0.jar` into the scratch server's `plugins/` directory. Floodgate is optional for this probe.
4. Package the compiled test fixture from the project root:

```sh
jar --create --file cards-menu-probe.jar \
	-C target/test-classes io/github/jackkitto/cards/probe \
	-C integration plugin.yml
```

5. Move `cards-menu-probe.jar` into the scratch server's `plugins/` directory.
6. Start Paper using Java 25. After startup, the probe opens menus, sends blocked inventory actions, checks selections and stale callbacks, and shuts down.
7. Read `plugins/CardsMenuProbe/result.txt`. A successful run starts with `PASS:`.

The probe checks menu construction, shift clicks, number keys, drop keys, double clicks, offhand swaps, middle clicks, drag events, item drops, duplicate callbacks, selection persistence after close, stale-round refresh, stale-page callbacks, and isolation from survival inventory. With Floodgate installed, it also constructs a native form and checks that sending to a nonexistent player returns false. Actual Java and Bedrock clients remain necessary for the checks in the [validation record](../docs/validation.md).
