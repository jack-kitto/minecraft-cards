package io.github.jackkitto.cards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public final class CardsPlugin extends JavaPlugin implements Listener, TabCompleter {
    Game game;
    Map<String, Deck> packs;
    final List<String> selectedPacks = new ArrayList<>();
    int goal;
    private Game.Rules rules;
    private Menus menus;
    private final Set<UUID> watchers = new HashSet<>();
    private Location table;
    private TextDisplay display;
    private String lastAnnouncement = "";

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            rules = new Game.Rules(getConfig().getInt("min-players"), getConfig().getInt("max-players"),
                    getConfig().getInt("hand-size"), getConfig().getInt("winning-score"), getConfig().getInt("submission-seconds"),
                    getConfig().getInt("judge-seconds"), getConfig().getInt("reconnect-seconds"), getConfig().getInt("result-seconds"));
            goal = rules.winningScore();
            Files.createDirectories(getDataFolder().toPath().resolve("decks"));
            if (!Files.exists(getDataFolder().toPath().resolve("decks/minecraft.json"))) saveResource("decks/minecraft.json", false);
            packs = PackLoader.load(getDataFolder().toPath().resolve("decks"));
            selectedPacks.addAll(getConfig().getStringList("packs"));
            PackLoader.combine(packs, selectedPacks).requireCapacity(rules.maxPlayers(), rules.handSize());
            game = new Game(rules, System::currentTimeMillis, new Random());
            menus = new Menus(this);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(menus, this);
            Objects.requireNonNull(getCommand("cards")).setExecutor(this);
            getCommand("cards").setTabCompleter(this);
            loadTable();
            getServer().getScheduler().runTaskTimer(this, () -> { game.tick(); sync(); }, 20, 20);
            getLogger().info("MinecraftCards ready. " + packs.size() + " pack(s). Paper 26.3 / Java 25. No match recovery on restart.");
        } catch (IOException | IllegalArgumentException ex) {
            getLogger().severe("MinecraftCards could not start: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (menus != null) menus.closeAll();
        if (display != null) display.remove();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        attempt(sender, () -> {
            String action = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
            if (action.equals("help")) { help(sender); return; }
            if (action.equals("status")) { sender.sendMessage(Component.text(publicSummary())); return; }
            if (action.equals("check")) {
                requireAdmin(sender);
                Map<String, Deck> checked = PackLoader.load(getDataFolder().toPath().resolve("decks"));
                Deck deck = PackLoader.combine(checked, selectedPacks);
                deck.requireCapacity(rules.maxPlayers(), rules.handSize());
                sender.sendMessage(Component.text("Packs valid: " + checked.size() + ". Selected deck: " + deck.prompts().size() + " prompts, " + deck.answers().size() + " answers."));
                return;
            }
            if (action.equals("reload")) {
                requireAdmin(sender);
                if (game.phase() != Game.Phase.LOBBY && game.phase() != Game.Phase.FINISHED) throw new IllegalArgumentException("End the match before reloading packs.");
                Map<String, Deck> loaded = PackLoader.load(getDataFolder().toPath().resolve("decks"));
                PackLoader.combine(loaded, selectedPacks).requireCapacity(rules.maxPlayers(), rules.handSize());
                packs = loaded;
                sender.sendMessage(Component.text("Packs reloaded. Rule and table settings load on server restart."));
                return;
            }
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("Use this command as a player. Console: cards help, status, check, reload.");
            if (!player.hasPermission("cards.play")) throw new IllegalArgumentException("You need cards.play.");
            UUID id = player.getUniqueId();
            switch (action) {
                case "open", "watch" -> { watchers.add(id); menus.open(player); }
                case "join" -> { join(player); menus.open(player); }
                case "leave" -> { game.leave(id); menus.open(player); }
                case "start" -> { start(player); menus.open(player); }
                case "pause" -> { requireControl(player); game.pause(); menus.open(player); }
                case "skip" -> { requireControl(player); game.skip(); menus.open(player); }
                case "end" -> { requireControl(player); game.end(); menus.open(player); }
                case "host" -> {
                    requireAdmin(player);
                    if (!game.contains(id)) game.join(id, player.getName());
                    game.takeHost(id);
                    menus.open(player);
                }
                case "score" -> {
                    requireControl(player); requireLobby();
                    if (args.length != 2) throw new IllegalArgumentException("Use /cards score <1-100>.");
                    int value = Integer.parseInt(args[1]);
                    if (value < 1 || value > 100) throw new IllegalArgumentException("Winning score must be 1-100.");
                    goal = value;
                    player.sendMessage(Component.text("Next match wins at " + goal + " points."));
                }
                case "packs" -> {
                    if (args.length == 1) { player.sendMessage(Component.text("Available: " + String.join(", ", packs.keySet()) + ". Selected: " + String.join(", ", selectedPacks))); return; }
                    requireControl(player); requireLobby();
                    List<String> names = List.of(args[1].split(",", -1));
                    PackLoader.combine(packs, names).requireCapacity(rules.maxPlayers(), rules.handSize());
                    selectedPacks.clear(); selectedPacks.addAll(names);
                    player.sendMessage(Component.text("Next match packs: " + String.join(", ", names)));
                }
                case "table" -> {
                    requireAdmin(player);
                    if (args.length == 2 && args[1].equals("clear")) {
                        table = null; getConfig().set("table", null); saveConfig(); updateDisplay();
                        player.sendMessage(Component.text("Table removed. /cards still opens the game."));
                    } else {
                        var block = player.getTargetBlockExact(6);
                        if (block == null || block.isEmpty()) throw new IllegalArgumentException("Look at a solid block within six blocks, then use /cards table.");
                        table = block.getLocation();
                        getConfig().set("table.world", table.getWorld().getUID().toString());
                        getConfig().set("table.x", table.getBlockX()); getConfig().set("table.y", table.getBlockY()); getConfig().set("table.z", table.getBlockZ());
                        saveConfig(); updateDisplay();
                        player.sendMessage(Component.text("Table set. Right-click this block to open Cards."));
                    }
                }
                default -> help(player);
            }
        });
        if (game != null) sync();
        return true;
    }

    void join(Player player) {
        if (!player.hasPermission("cards.play")) throw new IllegalArgumentException("You need cards.play.");
        if (game.host() == null && !player.hasPermission("cards.host")) throw new IllegalArgumentException("A player with cards.host must open the lobby first.");
        game.join(player.getUniqueId(), player.getName());
        watchers.add(player.getUniqueId());
        player.sendMessage(Component.text("Joined Cards. New players receive a hand at the next round boundary."));
    }

    boolean canControl(Player player) {
        return player.hasPermission("cards.admin") || player.getUniqueId().equals(game.host()) && player.hasPermission("cards.host");
    }
    void requireControl(Player player) { if (!canControl(player)) throw new IllegalArgumentException("Only the host with cards.host, or an admin, can do that."); }
    private void requireAdmin(CommandSender sender) { if (!sender.hasPermission("cards.admin")) throw new IllegalArgumentException("You need cards.admin."); }
    private void requireLobby() {
        if (game.phase() != Game.Phase.LOBBY && game.phase() != Game.Phase.FINISHED) throw new IllegalArgumentException("Change packs or goal before starting the next match.");
    }
    void start(Player player) {
        requireControl(player);
        game.start(PackLoader.combine(packs, selectedPacks), goal);
    }
    void togglePack(Player player, String name) {
        requireControl(player); requireLobby();
        if (selectedPacks.contains(name)) selectedPacks.remove(name); else selectedPacks.add(name);
    }

    @FunctionalInterface interface Action { void run() throws IOException; }
    void attempt(CommandSender sender, Action action) {
        try { action.run(); }
        catch (NumberFormatException ex) { sender.sendMessage(Component.text("Enter a whole number from 1 to 100.", NamedTextColor.RED)); }
        catch (IllegalArgumentException | IOException ex) { sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED)); }
    }

    String publicSummary() { return summary(true); }
    private String summary(boolean timer) {
        Game.Board b = game.board();
        StringBuilder text = new StringBuilder("Cards | ").append(b.phase().name().toLowerCase(Locale.ROOT));
        if (b.paused()) text.append(" | PAUSED");
        if (!b.prompt().isEmpty()) {
            text.append("\nRound ").append(b.round()).append(" | Pick ").append(b.picks()).append(" | Judge: ").append(b.judge());
            text.append("\n").append(b.prompt());
            text.append("\nSubmitted: ").append(b.submitted()).append('/').append(b.expected());
            if (timer && (b.phase() == Game.Phase.SUBMIT || b.phase() == Game.Phase.REVEAL)) text.append(" | ").append(b.secondsLeft()).append("s");
        }
        text.append("\n").append(b.result());
        if (!b.entries().isEmpty()) {
            Game.PublicEntry latest = b.entries().getLast();
            text.append("\nAnswer ").append(latest.number()).append(": ").append(latest.text());
        }
        text.append("\nScores, first to ").append(b.goal()).append(':');
        for (Game.Standing score : b.scores()) text.append("\n").append(score.name()).append(": ").append(score.score())
                .append(score.online() ? score.waiting() ? " (next round)" : "" : " (reconnecting)");
        if (b.host() != null) text.append("\nHost: ").append(b.scores().stream().filter(s -> s.id().equals(b.host())).map(Game.Standing::name).findFirst().orElse("None"));
        return text.toString();
    }

    void sync() {
        String announcement = summary(false);
        if (announcement.equals(lastAnnouncement)) return;
        lastAnnouncement = announcement;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (watchers.contains(player.getUniqueId()) || game.contains(player.getUniqueId()) || nearTable(player)) {
                player.sendMessage(Component.text(announcement, NamedTextColor.AQUA));
            }
        }
        if (display != null && display.isValid()) display.text(Component.text(announcement));
    }
    private boolean nearTable(Player player) {
        double radius = Math.max(1, Math.min(64, getConfig().getDouble("board-radius", 16)));
        return table != null && player.getWorld().equals(table.getWorld()) && player.getLocation().distanceSquared(table) <= radius * radius;
    }

    private void loadTable() {
        String worldId = getConfig().getString("table.world");
        if (worldId == null) return;
        World world = Bukkit.getWorld(UUID.fromString(worldId));
        if (world == null) { getLogger().warning("Table world is not loaded. Set /cards table in the intended world."); return; }
        table = new Location(world, getConfig().getInt("table.x"), getConfig().getInt("table.y"), getConfig().getInt("table.z"));
        updateDisplay();
    }
    private void updateDisplay() {
        if (display != null) { display.remove(); display = null; }
        if (table == null || !getConfig().getBoolean("board-display")) return;
        display = table.getWorld().spawn(table.clone().add(0.5, 2.5, 0.5), TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setLineWidth(260);
            entity.setSeeThrough(false);
            entity.text(Component.text(summary(false)));
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || table == null || event.getClickedBlock() == null || !event.getClickedBlock().getLocation().equals(table)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("cards.play")) return;
        watchers.add(player.getUniqueId());
        menus.open(player);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        game.disconnected(id); menus.forget(id); watchers.remove(id); sync();
    }
    @EventHandler public void connected(PlayerJoinEvent event) {
        game.connected(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        sync();
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("/cards [join|leave|watch|status] opens the table. Select cards, Preview / Submit, then let the judge reveal and choose.\n"
                + "Host: /cards start | score <1-100> | packs <name,name> | pause | skip | end\n"
                + "Admin: /cards table [clear] | host | check | reload\n"
                + "Closing a menu keeps your picks. Use /cards again after a round changes."));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("join", "leave", "watch", "status", "help"));
            if (sender instanceof Player player && canControl(player)) choices.addAll(List.of("start", "score", "packs", "pause", "skip", "end"));
            if (sender.hasPermission("cards.admin")) choices.addAll(List.of("table", "host", "check", "reload"));
            return choices.stream().filter(c -> c.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("packs")) return packs.keySet().stream().filter(p -> p.startsWith(args[1])).toList();
        return List.of();
    }
}
