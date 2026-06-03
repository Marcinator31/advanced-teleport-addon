package de.server.atconfirm;

import io.github.niestrat99.advancedteleport.api.events.players.TeleportAcceptEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportDenyEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportRequestEvent;
import io.github.niestrat99.advancedteleport.api.TeleportRequestType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ATConfirmGUI extends JavaPlugin implements Listener {

    private NamespacedKey actionKey;

    // Settings per player (UUID in set = feature ON)
    private final Set<UUID> autoAccept       = new HashSet<>(); // tpauto
    private final Set<UUID> noConfirmGui     = new HashSet<>(); // skip confirm GUIs
    private final Set<UUID> blockTpa         = new HashSet<>(); // block incoming tpa
    private final Set<UUID> blockTpahere     = new HashSet<>(); // block incoming tpahere

    // Pending incoming requests
    private final Set<UUID> hasPendingRequest = new HashSet<>();

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("AdvancedTeleport") == null) {
            getLogger().severe("AdvancedTeleport not found! Disabling ATConfirmGUI.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        actionKey = new NamespacedKey(this, "action");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ATConfirmGUI enabled.");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Listener) this);
    }

    // ---------------------------------------------------------------------
    //  Track pending requests
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRequest(TeleportRequestEvent event) {
        Player receiver = event.getReceivingPlayer();
        Player sender   = event.getSendingPlayer();
        if (receiver == null || sender == null) return;

        boolean isHere = event.getRequestType() == TeleportRequestType.TPAHERE;

        // Block request if receiver has that type disabled — cancel the event.
        if (isHere && blockTpahere.contains(receiver.getUniqueId())) {
            event.setCancelled(true);
            sender.sendMessage(ChatColor.RED + receiver.getName() + " is not accepting TPAHere requests.");
            return;
        }
        if (!isHere && blockTpa.contains(receiver.getUniqueId())) {
            event.setCancelled(true);
            sender.sendMessage(ChatColor.RED + receiver.getName() + " is not accepting TPA requests.");
            return;
        }

        hasPendingRequest.add(receiver.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAccept(TeleportAcceptEvent event) {
        Player receiver = event.getReceivingPlayer();
        if (receiver != null) hasPendingRequest.remove(receiver.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeny(TeleportDenyEvent event) {
        Player receiver = event.getReceivingPlayer();
        if (receiver != null) hasPendingRequest.remove(receiver.getUniqueId());
    }

    // ---------------------------------------------------------------------
    //  Death: disable tpauto
    // ---------------------------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        hasPendingRequest.remove(player.getUniqueId());
        if (autoAccept.remove(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline())
                    sendActionBar(player, Component.text("tpauto was disabled on death", NamedTextColor.RED), 40L);
            }, 1L);
        }
    }

    // ---------------------------------------------------------------------
    //  Commands
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "tpa" -> {
                if (args.length < 1) { player.sendMessage(ChatColor.RED + "Usage: /tpa <player>"); return true; }
                if (noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpa " + args[0]);
                } else {
                    openSendConfirm(player, args[0], "tpa");
                }
                return true;
            }
            case "tpahere" -> {
                if (args.length < 1) { player.sendMessage(ChatColor.RED + "Usage: /tpahere <player>"); return true; }
                if (noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpahere " + args[0]);
                } else {
                    openSendConfirm(player, args[0], "tpahere");
                }
                return true;
            }
            case "tpaccept" -> {
                if (!hasPendingRequest.contains(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "You don't have any pending requests!");
                    return true;
                }
                if (autoAccept.contains(player.getUniqueId()) || noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpaccept");
                } else {
                    openAcceptConfirmFor(player, ChatColor.DARK_GRAY + "Accept request?");
                }
                return true;
            }
            case "tpauto" -> {
                if (autoAccept.contains(player.getUniqueId())) {
                    autoAccept.remove(player.getUniqueId());
                    sendActionBar(player, Component.text("You disabled tpauto", NamedTextColor.RED), 40L);
                } else {
                    autoAccept.add(player.getUniqueId());
                    sendActionBarPermanent(player, Component.text("✔ tpauto enabled", NamedTextColor.GREEN));
                }
                return true;
            }
            case "tpsettings" -> {
                openSettings(player);
                return true;
            }
            default -> { return false; }
        }
    }

    // ---------------------------------------------------------------------
    //  Settings GUI
    // ---------------------------------------------------------------------

    private void openSettings(Player player) {
        Inventory inv = Bukkit.createInventory(player, 27, ChatColor.DARK_GRAY + "Teleport Settings");

        UUID id = player.getUniqueId();

        // Slot 10: Confirm GUIs toggle
        boolean confirmOn = !noConfirmGui.contains(id);
        inv.setItem(10, settingButton(
                confirmOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                (confirmOn ? ChatColor.GREEN : ChatColor.RED) + "Confirm GUIs",
                List.of(
                        ChatColor.GRAY + "Show a confirmation GUI before",
                        ChatColor.GRAY + "sending or accepting requests.",
                        "",
                        ChatColor.YELLOW + "Currently: " + (confirmOn ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled")
                ),
                "toggle_confirm"));

        // Slot 13: TPAuto toggle
        boolean autoOn = autoAccept.contains(id);
        inv.setItem(13, settingButton(
                autoOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                (autoOn ? ChatColor.GREEN : ChatColor.RED) + "TPAuto",
                List.of(
                        ChatColor.GRAY + "Automatically accept all",
                        ChatColor.GRAY + "incoming TPA requests.",
                        "",
                        ChatColor.YELLOW + "Currently: " + (autoOn ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled")
                ),
                "toggle_tpauto"));

        // Slot 15: Receive TPA toggle
        boolean tpaOn = !blockTpa.contains(id);
        inv.setItem(15, settingButton(
                tpaOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                (tpaOn ? ChatColor.GREEN : ChatColor.RED) + "Receive TPA",
                List.of(
                        ChatColor.GRAY + "Allow others to send you",
                        ChatColor.GRAY + "TPA requests.",
                        "",
                        ChatColor.YELLOW + "Currently: " + (tpaOn ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled")
                ),
                "toggle_tpa"));

        // Slot 16: Receive TPAHere toggle
        boolean tpahereOn = !blockTpahere.contains(id);
        inv.setItem(16, settingButton(
                tpahereOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                (tpahereOn ? ChatColor.GREEN : ChatColor.RED) + "Receive TPAHere",
                List.of(
                        ChatColor.GRAY + "Allow others to send you",
                        ChatColor.GRAY + "TPAHere requests.",
                        "",
                        ChatColor.YELLOW + "Currently: " + (tpahereOn ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled")
                ),
                "toggle_tpahere"));

        player.openInventory(inv);
    }

    private ItemStack settingButton(Material material, String name, List<String> lore, String action) {
        return button(material, name, lore, action);
    }

    // Refresh settings GUI after a toggle click.
    private void refreshSettings(Player player) {
        // Close and reopen on next tick so inventory updates cleanly.
        Bukkit.getScheduler().runTaskLater(this, () -> openSettings(player), 1L);
    }

    // ---------------------------------------------------------------------
    //  Action bar helpers
    // ---------------------------------------------------------------------

    private void sendActionBar(Player player, Component message, long durationTicks) {
        player.sendActionBar(message);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendActionBar(Component.empty());
        }, durationTicks);
    }

    private void sendActionBarPermanent(Player player, Component message) {
        player.sendActionBar(message);
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || !autoAccept.contains(player.getUniqueId())) {
                task.cancel();
                if (player.isOnline()) player.sendActionBar(Component.empty());
                return;
            }
            player.sendActionBar(message);
        }, 40L, 40L);
    }

    // ---------------------------------------------------------------------
    //  Confirm GUIs
    // ---------------------------------------------------------------------

    private void openSendConfirm(Player player, String target, String baseCmd) {
        String pretty = baseCmd.equals("tpahere") ? "TPA-Here" : "TPA";
        Inventory inv = Bukkit.createInventory(player, 27,
                ChatColor.DARK_GRAY + "Send " + pretty + " to " + target + "?");
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.GREEN + "Confirm",
                List.of(ChatColor.GRAY + "Send the request to " + target + "."),
                "[close];advancedteleport:" + baseCmd + " " + target));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                ChatColor.RED + "Cancel",
                List.of(ChatColor.GRAY + "Do not send the request."),
                "[close]"));
        player.openInventory(inv);
    }

    private void openAcceptConfirmFor(Player player, String title) {
        Inventory inv = Bukkit.createInventory(player, 27, title);
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.GREEN + "Accept",
                List.of(ChatColor.GRAY + "Accept the teleport request."),
                "[close];advancedteleport:tpaccept"));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                ChatColor.RED + "Deny",
                List.of(ChatColor.GRAY + "Deny the teleport request."),
                "[close];advancedteleport:tpdeny"));
        player.openInventory(inv);
    }

    private ItemStack button(Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------------------------------------------------------------
    //  Click handling
    // ---------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        event.setCancelled(true);
        UUID id = player.getUniqueId();

        switch (action) {
            case "toggle_confirm" -> {
                if (noConfirmGui.contains(id)) {
                    noConfirmGui.remove(id);
                    player.sendMessage(ChatColor.GREEN + "Confirm GUIs enabled.");
                } else {
                    noConfirmGui.add(id);
                    player.sendMessage(ChatColor.RED + "Confirm GUIs disabled.");
                }
                refreshSettings(player);
            }
            case "toggle_tpauto" -> {
                if (autoAccept.contains(id)) {
                    autoAccept.remove(id);
                    sendActionBar(player, Component.text("You disabled tpauto", NamedTextColor.RED), 40L);
                } else {
                    autoAccept.add(id);
                    sendActionBarPermanent(player, Component.text("✔ tpauto enabled", NamedTextColor.GREEN));
                }
                refreshSettings(player);
            }
            case "toggle_tpa" -> {
                if (blockTpa.contains(id)) {
                    blockTpa.remove(id);
                    player.sendMessage(ChatColor.GREEN + "You are now accepting TPA requests.");
                } else {
                    blockTpa.add(id);
                    player.sendMessage(ChatColor.RED + "You are no longer accepting TPA requests.");
                }
                refreshSettings(player);
            }
            case "toggle_tpahere" -> {
                if (blockTpahere.contains(id)) {
                    blockTpahere.remove(id);
                    player.sendMessage(ChatColor.GREEN + "You are now accepting TPAHere requests.");
                } else {
                    blockTpahere.add(id);
                    player.sendMessage(ChatColor.RED + "You are no longer accepting TPAHere requests.");
                }
                refreshSettings(player);
            }
            default -> {
                // Normal GUI buttons ([close], advancedteleport:...)
                for (String part : action.split(";")) {
                    String cmd = part.trim();
                    if (cmd.isEmpty()) continue;
                    if (cmd.equalsIgnoreCase("[close]")) {
                        player.closeInventory();
                    } else {
                        player.performCommand(cmd);
                    }
                }
            }
        }
    }
}
