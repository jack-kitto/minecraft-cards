package io.github.jackkitto.cards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.*;
import java.util.*;

final class Menus implements Listener {
    record Button(String label, String detail, Material icon, Runnable action) {}
    record Screen(String title, String body, List<Button> buttons) {}
    private final CardsPlugin plugin;
    private final Map<UUID, Long> sessions = new HashMap<>();
    private long serial;
    private final boolean floodgate;

    Menus(CardsPlugin plugin) {
        this.plugin = plugin;
        floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    void open(Player player) {
        Game game = plugin.game;
        Game.Board board = game.board();
        Game.Hand hand = game.hand(player.getUniqueId());
        long round = board.round();
        UUID id = player.getUniqueId();
        List<Button> buttons = new ArrayList<>();
        String body = plugin.publicSummary() + "\n\n";
        if (!game.contains(id)) {
            body += "You are watching. Join to receive a private hand at the next round boundary.";
            buttons.add(button("Join table", "New players enter at a round boundary.", Material.EMERALD, () -> { plugin.join(player); open(player); }));
        } else if (hand.canSelect()) {
            body += "Pick " + board.picks() + " cards in order. Click a chosen card to remove it.\nSelected: "
                    + hand.selectedIds().size() + "/" + board.picks();
            for (int i = 0; i < hand.cards().size(); i++) {
                Deck.Card card = hand.cards().get(i);
                int selected = hand.selectedIds().indexOf(card.id());
                String label = (selected < 0 ? (i + 1) + ". " : "Picked " + (selected + 1) + ": ") + card.text();
                buttons.add(button(label, "Prompt: " + board.prompt() + "\n\nAnswer: " + card.text(),
                        selected < 0 ? Material.PAPER : Material.LIME_DYE,
                        () -> card(player, round, card)));
            }
            buttons.add(button("Preview / Submit", "Read the completed answer before you submit.", Material.WRITABLE_BOOK,
                    () -> preview(player, round)));
            buttons.add(button("Undo last pick", "Remove the last selected card.", Material.ARROW,
                    () -> { game.undo(id, round); open(player); }));
        } else if (hand.submitted()) {
            body += "Your answer is locked in. Use Private answer to read it.";
            buttons.add(button("Private answer", "Only you can see your submitted answer here.", Material.PAPER,
                    () -> show(player, new Screen("Your answer", game.hand(id).preview(), List.of(back(player))))));
        } else {
            body += id.equals(game.judge()) ? "You are the judge this round." : "Your hand returns when you can select in the next round.";
        }
        if (board.phase() == Game.Phase.REVEAL) {
            if (id.equals(game.judge()) && board.entries().size() < board.submitted()) {
                int count = board.entries().size();
                buttons.add(button("Reveal next answer", "Reveal one anonymous answer to everyone.", Material.ENDER_EYE,
                        () -> { game.reveal(id, round, count); open(player); }));
            }
        }
        for (Game.PublicEntry entry : board.entries()) {
            buttons.add(button("Answer " + entry.number(), entry.text(), Material.MAP,
                    () -> entry(player, round, entry.number(), entry.text())));
        }
        if (plugin.canControl(player)) buttons.add(button("Host controls", "Start, choose packs, pause, skip, or end.", Material.GOLD_INGOT, () -> host(player)));
        if (game.contains(id)) buttons.add(button("Leave table", "Your hand and score are released. You can watch or join again.", Material.OAK_DOOR,
                () -> confirm(player, "Leave table?", "You lose your hand and score.", () -> { game.leave(id); open(player); })));
        buttons.add(button("Refresh table", "Open the latest round state.", Material.COMPASS, () -> open(player)));
        show(player, new Screen("Cards | " + board.phase().name().toLowerCase(Locale.ROOT), body, buttons));
    }

    private void card(Player player, long round, Deck.Card card) {
        boolean selected = plugin.game.hand(player.getUniqueId()).selectedIds().contains(card.id());
        show(player, new Screen("Read answer card", "Prompt: " + plugin.game.board().prompt() + "\n\nAnswer: " + card.text(),
                List.of(button(selected ? "Remove this pick" : "Pick this card", "Return to your hand after choosing.", Material.LIME_DYE,
                        () -> { plugin.game.toggle(player.getUniqueId(), round, card.id()); open(player); }), back(player))));
    }

    private void preview(Player player, long round) {
        Game.Hand hand = plugin.game.hand(player.getUniqueId());
        List<Button> buttons = new ArrayList<>();
        if (hand.canSelect() && hand.selectedIds().size() == plugin.game.board().picks()) {
            buttons.add(button("Submit answer", "Lock these cards in this order.", Material.EMERALD_BLOCK,
                    () -> { plugin.game.submit(player.getUniqueId(), round); open(player); }));
        }
        buttons.add(button("Undo last pick", "Return to your hand after undoing.", Material.ARROW,
                () -> { plugin.game.undo(player.getUniqueId(), round); open(player); }));
        buttons.add(back(player));
        show(player, new Screen("Preview your answer", "Selected " + hand.selectedIds().size() + "/" + plugin.game.board().picks()
                + "\n\n" + hand.preview(), buttons));
    }

    private void entry(Player player, long round, int number, String text) {
        List<Button> buttons = new ArrayList<>();
        if (player.getUniqueId().equals(plugin.game.judge()) && plugin.game.phase() == Game.Phase.REVEAL) {
            buttons.add(button("Choose answer " + number, "Reveal all answers before choosing a winner.", Material.GOLD_BLOCK,
                    () -> confirm(player, "Award one point?", text,
                            () -> { plugin.game.choose(player.getUniqueId(), round, number); open(player); })));
        }
        buttons.add(back(player));
        show(player, new Screen("Answer " + number, text, buttons));
    }

    private void host(Player player) {
        plugin.requireControl(player);
        List<Button> buttons = new ArrayList<>();
        Game game = plugin.game;
        if (game.phase() == Game.Phase.LOBBY || game.phase() == Game.Phase.FINISHED) {
            buttons.add(button("Start match", "Use the selected packs and winning score.", Material.EMERALD_BLOCK,
                    () -> { plugin.start(player); open(player); }));
            for (String name : plugin.packs.keySet()) buttons.add(button((plugin.selectedPacks.contains(name) ? "Enabled: " : "Disabled: ") + name,
                    "Toggle this pack for the next match.", Material.BOOK, () -> { plugin.togglePack(player, name); host(player); }));
            buttons.add(button("Winning score: " + plugin.goal, "Cycle 1 through 10 points. Commands accept up to 100.", Material.GOLD_NUGGET,
                    () -> { plugin.requireControl(player); plugin.goal = plugin.goal % 10 + 1; host(player); }));
        } else {
            buttons.add(button(game.isPaused() ? "Resume" : "Pause", "Freeze or resume round and reconnect timers.", Material.CLOCK,
                    () -> { plugin.requireControl(player); game.pause(); open(player); }));
            buttons.add(button("Skip round", "No point is awarded.", Material.ARROW,
                    () -> { plugin.requireControl(player); game.skip(); open(player); }));
            buttons.add(button("End match", "Return to the lobby and keep final scores until the next start.", Material.REDSTONE_BLOCK,
                    () -> confirm(player, "End match?", "Current submissions are discarded.",
                            () -> { plugin.requireControl(player); game.end(); open(player); })));
        }
        buttons.add(back(player));
        show(player, new Screen("Host controls", "Packs: " + String.join(", ", plugin.selectedPacks) + "\nWinning score: " + plugin.goal, buttons));
    }

    private void confirm(Player player, String title, String body, Runnable action) {
        show(player, new Screen(title, body, List.of(button("Confirm", body, Material.EMERALD_BLOCK, action), back(player))));
    }
    private Button back(Player player) { return button("Back to table", "Return without changing your selection.", Material.ARROW, () -> open(player)); }
    private Button button(String label, String detail, Material icon, Runnable action) { return new Button(label, detail, icon, action); }

    private void show(Player player, Screen screen) {
        show(player, screen, 0);
    }

    private void show(Player player, Screen screen, int page) {
        long token = ++serial;
        long round = plugin.game.round();
        if (floodgate && BedrockForms.isBedrock(player.getUniqueId())) {
            sessions.put(player.getUniqueId(), token);
            boolean sent = BedrockForms.show(player.getUniqueId(), screen.title(), screen.body(),
                    screen.buttons().stream().map(Button::label).toList(),
                    index -> Bukkit.getScheduler().runTask(plugin, () -> dispatch(player, token, round, screen.buttons().get(index).action())));
            if (!sent) {
                sessions.remove(player.getUniqueId(), token);
                player.sendMessage(Component.text("The Bedrock form could not open. Run /cards again.", NamedTextColor.RED));
            }
            return;
        }
        Holder holder = new Holder(player.getUniqueId(), token, round, screen);
        List<String> lines = wrap(screen.body(), 42);
        for (int i = 0; i < Math.min(18, (lines.size() + 5) / 6); i++) {
            holder.inventory.setItem(i, item(Material.BOOK, "Table text " + (i + 1),
                    lines.subList(i * 6, Math.min(lines.size(), (i + 1) * 6))));
        }
        int pages = Math.max(1, (screen.buttons().size() + 26) / 27);
        renderButtons(holder, player, page, pages);
        player.openInventory(holder.inventory);
        sessions.put(player.getUniqueId(), token);
    }

    private void renderButtons(Holder holder, Player player, int page, int pages) {
        holder.actions.clear();
        for (int slot = 18; slot < 54; slot++) holder.inventory.setItem(slot, null);
        for (int i = page * 27; i < Math.min(holder.screen.buttons().size(), (page + 1) * 27); i++) {
            Button button = holder.screen.buttons().get(i);
            int slot = 18 + i % 27;
            holder.inventory.setItem(slot, item(button.icon(), button.label(), wrap(button.detail(), 42)));
            holder.actions.put(slot, button.action());
        }
        if (page > 0) {
            holder.inventory.setItem(45, item(Material.ARROW, "Previous page", List.of()));
            holder.actions.put(45, () -> show(player, holder.screen, page - 1));
        }
        if (page + 1 < pages) {
            holder.inventory.setItem(53, item(Material.ARROW, "Next page", List.of()));
            holder.actions.put(53, () -> show(player, holder.screen, page + 1));
        }
    }

    private void dispatch(Player player, long token, long round, Runnable action) {
        if (!player.isOnline() || !sessions.remove(player.getUniqueId(), token)) return;
        plugin.attempt(player, () -> {
            if (plugin.game.round() != round) { open(player); return; }
            try { action.run(); }
            catch (IllegalArgumentException ex) { open(player); throw ex; }
        });
        plugin.sync();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        Runnable action = holder.actions.get(event.getRawSlot());
        if (action != null) Bukkit.getScheduler().runTask(plugin, () -> dispatch(player, holder.token, holder.round, action));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder holder) sessions.remove(event.getPlayer().getUniqueId(), holder.token);
    }
    void forget(UUID id) { sessions.remove(id); }
    void closeAll() {
        sessions.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder) player.closeInventory();
    }

    private static ItemStack item(Material material, String label, List<String> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(label.length() > 65 ? label.substring(0, 62) + "..." : label, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                while (word.length() > width) {
                    if (!line.isEmpty()) { lines.add(line.toString()); line.setLength(0); }
                    lines.add(word.substring(0, width)); word = word.substring(width);
                }
                if (line.length() + word.length() + 1 > width) { lines.add(line.toString()); line.setLength(0); }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final long token, round;
        final Screen screen;
        final Inventory inventory;
        final Map<Integer, Runnable> actions = new HashMap<>();
        Holder(UUID owner, long token, long round, Screen screen) {
            this.owner = owner; this.token = token; this.round = round; this.screen = screen;
            inventory = Bukkit.createInventory(this, 54, Component.text(screen.title()));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
