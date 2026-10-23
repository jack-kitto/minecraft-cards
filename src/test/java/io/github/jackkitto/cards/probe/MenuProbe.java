package io.github.jackkitto.cards.probe;

import io.github.jackkitto.cards.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Run only in a disposable server. Synthetic players exercise events, not client rendering. */
public final class MenuProbe extends JavaPlugin {
    private Game game;
    private Object menus;
    private Method open;
    private Player player;
    private Inventory top, oldTop, bottom;
    private final List<UUID> ids = List.of(new UUID(0, 901), new UUID(0, 902), new UUID(0, 903));
    private int step;
    private int pageActions;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::runStep, 20); }

    private void runStep() {
        try {
            switch (step++) {
                case 0 -> {
                    JavaPlugin cards = (JavaPlugin) Bukkit.getPluginManager().getPlugin("MinecraftCards");
                    if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                        Class<?> forms = Class.forName("io.github.jackkitto.cards.BedrockForms", true, cards.getClass().getClassLoader());
                        Method send = forms.getDeclaredMethod("show", UUID.class, String.class, String.class, List.class, java.util.function.IntConsumer.class);
                        send.setAccessible(true);
                        boolean sent = (boolean) send.invoke(null, ids.get(1), "Fixture", "Long card " + "x".repeat(350), List.of("Read"), (java.util.function.IntConsumer) index -> {});
                        check(!sent, "A form was sent to a nonexistent player.");
                    }
                    game = (Game) field(cards, "game");
                    menus = field(cards, "menus");
                    open = menus.getClass().getDeclaredMethod("open", Player.class); open.setAccessible(true);
                    for (int i = 0; i < ids.size(); i++) game.join(ids.get(i), "Fixture" + i);
                    Deck deck = PackLoader.combine(PackLoader.load(cards.getDataFolder().toPath().resolve("decks")), List.of("minecraft"));
                    game.start(deck, 5);
                    bottom = Bukkit.createInventory(null, 36);
                    bottom.setItem(0, new ItemStack(Material.DIAMOND, 7));
                    player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> ids.get(1);
                        case "getName" -> "Fixture1";
                        case "isOnline", "hasPermission" -> true;
                        case "openInventory" -> { top = (Inventory) args[0]; yield view(top); }
                        case "getOpenInventory" -> view(top);
                        case "sendMessage", "closeInventory" -> null;
                        case "getInventory" -> throw new AssertionError("The menu accessed survival inventory.");
                        case "hashCode" -> 902;
                        case "equals" -> p == args[0];
                        case "toString" -> "Synthetic card-menu player";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
                    open.invoke(menus, player);
                    check(top.getSize() == 54, "Menu did not open.");
                    for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.NUMBER_KEY,
                            ClickType.DROP, ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.SWAP_OFFHAND, ClickType.MIDDLE)) {
                        InventoryClickEvent event = click(top, type);
                        check(event.isCancelled(), "Inventory click escaped cancellation: " + type);
                    }
                    InventoryDragEvent drag = new InventoryDragEvent(view(top), new ItemStack(Material.STONE),
                            new ItemStack(Material.STONE, 2), false, Map.of(18, new ItemStack(Material.STONE)));
                    Bukkit.getPluginManager().callEvent(drag);
                    check(drag.isCancelled(), "Drag escaped cancellation.");
                    Item dropped = proxy(Item.class, (p, m, a) -> null);
                    PlayerDropItemEvent drop = new PlayerDropItemEvent(player, dropped);
                    Bukkit.getPluginManager().callEvent(drop);
                    check(drop.isCancelled(), "Drop escaped cancellation.");
                }
                case 1 -> {
                    check(game.hand(ids.get(1)).selectedIds().isEmpty(), "Blocked clicks selected a card.");
                    click(top, ClickType.LEFT);
                }
                case 2 -> {
                    check(game.hand(ids.get(1)).selectedIds().isEmpty(), "Reading a card selected it.");
                    oldTop = top;
                    click(oldTop, ClickType.LEFT);
                    click(oldTop, ClickType.LEFT);
                }
                case 3 -> {
                    check(game.hand(ids.get(1)).selectedIds().size() == 1, "Duplicate callback toggled a card twice.");
                    Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(view(top)));
                    open.invoke(menus, player);
                    check(game.hand(ids.get(1)).selectedIds().size() == 1, "Closing lost selection.");
                    oldTop = top;
                    game.skip();
                    click(oldTop, ClickType.LEFT);
                }
                case 4 -> {
                    check(game.hand(ids.get(1)).selectedIds().isEmpty(), "Stale menu selected in a new round.");
                    check(top != oldTop, "Stale click did not refresh the menu.");
                    check(bottom.getItem(0).getType() == Material.DIAMOND && bottom.getItem(0).getAmount() == 7,
                            "Survival inventory changed.");
                    ClassLoader loader = menus.getClass().getClassLoader();
                    Class<?> button = Class.forName("io.github.jackkitto.cards.Menus$Button", true, loader);
                    Constructor<?> makeButton = button.getDeclaredConstructor(String.class, String.class, Material.class, Runnable.class);
                    makeButton.setAccessible(true);
                    List<Object> buttons = new ArrayList<>();
                    for (int i = 0; i < 30; i++) buttons.add(makeButton.newInstance("Fixture " + i, "Page action", Material.PAPER, (Runnable) () -> pageActions++));
                    Class<?> screen = Class.forName("io.github.jackkitto.cards.Menus$Screen", true, loader);
                    Constructor<?> makeScreen = screen.getDeclaredConstructor(String.class, String.class, List.class);
                    makeScreen.setAccessible(true);
                    Method show = menus.getClass().getDeclaredMethod("show", Player.class, screen); show.setAccessible(true);
                    show.invoke(menus, player, makeScreen.newInstance("Pages", "Fixture page navigation", buttons));
                    oldTop = top;
                    click(top, ClickType.LEFT, 53);
                }
                case 5 -> {
                    check(top != oldTop, "Page did not receive a fresh menu session.");
                    click(oldTop, ClickType.LEFT);
                    click(top, ClickType.LEFT);
                }
                case 6 -> {
                    check(pageActions == 1, "A stale page callback executed.");
                    game.end(); ids.forEach(game::leave);
                    finish("PASS: menu creation, blocked shift/number/drop/double/offhand/middle clicks, drag/drop cancellation, selection persistence, duplicate callback guard, stale-round and stale-page guards, survival inventory isolation. Floodgate form construction checked when installed.");
                    return;
                }
            }
            Bukkit.getScheduler().runTaskLater(this, this::runStep, 2);
        } catch (Throwable ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Menu probe failed", ex);
            finish("FAIL: " + ex);
        }
    }

    private InventoryClickEvent click(Inventory inventory, ClickType type) {
        return click(inventory, type, 18);
    }
    private InventoryClickEvent click(Inventory inventory, ClickType type, int slot) {
        InventoryClickEvent event = new InventoryClickEvent(view(inventory), InventoryType.SlotType.CONTAINER,
                slot, type, InventoryAction.PICKUP_ALL, 0);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private InventoryView view(Inventory inventory) {
        return proxy(InventoryView.class, (p, method, args) -> switch (method.getName()) {
            case "getTopInventory" -> inventory;
            case "getBottomInventory" -> bottom;
            case "getPlayer" -> player;
            case "getType" -> InventoryType.CHEST;
            case "getInventory" -> (int) args[0] < 54 ? inventory : bottom;
            case "convertSlot" -> (int) args[0] < 54 ? args[0] : (int) args[0] - 54;
            case "getSlotType" -> InventoryType.SlotType.CONTAINER;
            case "getCursor" -> new ItemStack(Material.AIR);
            case "getItem" -> inventory.getItem((int) args[0]);
            case "getTitle", "getOriginalTitle" -> "Fixture";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private void finish(String result) {
        try { Files.createDirectories(getDataFolder().toPath()); Files.writeString(getDataFolder().toPath().resolve("result.txt"), result + "\n"); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        getLogger().info(result);
        Bukkit.shutdown();
    }
}
