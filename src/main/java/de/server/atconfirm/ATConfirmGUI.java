package de.server.atconfirm;

import io.github.niestrat99.advancedteleport.api.TeleportRequestType;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportAcceptEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportRequestEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.Location;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ATConfirmGUI extends JavaPlugin implements Listener {

    private NamespacedKey actionKey;

    private final Set<UUID> autoAccept   = new HashSet<>();
    private final Set<UUID> noConfirmGui = new HashSet<>();
    private final Set<UUID> blockTpa     = new HashSet<>();
    private final Set<UUID> blockTpahere = new HashSet<>();
    private final Map<UUID, BukkitTask> activeCountdowns = new HashMap<>();

    // Warmup duration — must match AT's warm-up-timer-duration in config.yml
    private static final int WARMUP_SECONDS = 5;

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
        activeCountdowns.values().forEach(BukkitTask::cancel);
        activeCountdowns.clear();
        HandlerList.unregisterAll((Listener) this);
    }

    // ---------------------------------------------------------------------
    //  Block TPA/TPAHere if disabled, auto-accept if tpauto on
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRequest(TeleportRequestEvent event) {
        Player receiver = event.getReceivingPlayer();
        Player sender   = event.getSendingPlayer();
        if (receiver == null || sender == null) return;

        boolean isHere = event.getRequestType() == TeleportRequestType.TPAHERE;

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

        if (autoAccept.contains(receiver.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (receiver.isOnline()) {
                    receiver.performCommand("advancedteleport:tpaccept");
                }
            }, 2L);
        }
    }

    // ---------------------------------------------------------------------
    //  Start action bar countdown when request is accepted
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAccept(TeleportAcceptEvent event) {
        Player receiver = event.getReceivingPlayer();
        if (receiver == null) return;
        startActionBarCountdown(receiver);
    }

    private void startActionBarCountdown(Player player) {
        // Cancel any existing countdown for this player
        cancelCountdown(player);

        final int[] remaining = {WARMUP_SECONDS};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                BukkitTask t = activeCountdowns.remove(player.getUniqueId());
                if (t != null) t.cancel();
                return;
            }
            if (remaining[0] > 0) {
                // Action bar countdown
                player.sendActionBar(Component.text(
                        "⏱ Teleporting in " + remaining[0] + "s... do not move!",
                        NamedTextColor.AQUA));
                // Tick sound: soft hat each second, higher on last second
                float pitch = remaining[0] == 1 ? 2.0f : 1.0f;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
                remaining[0]--;
            } else {
                // Done — clear action bar
                player.sendActionBar(Component.empty());
                BukkitTask t = activeCountdowns.remove(player.getUniqueId());
                if (t != null) t.cancel();
            }
        }, 0L, 20L);

        activeCountdowns.put(player.getUniqueId(), task);
    }

    // ---------------------------------------------------------------------
    //  Start countdown for RTP / Home / Spawn / Back commands
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().toLowerCase().trim();
        // Match /tpr, /rtp, /home, /spawn, /back and their namespaced forms
        if (msg.equals("/tpr") || msg.startsWith("/tpr ")
                || msg.equals("/home") || msg.startsWith("/home ")
                || msg.equals("/spawn") || msg.startsWith("/spawn ")
                || msg.equals("/back")
                || msg.equals("/advancedteleport:tpr") || msg.startsWith("/advancedteleport:tpr ")
                || msg.equals("/advancedteleport:home") || msg.startsWith("/advancedteleport:home ")
                || msg.equals("/advancedteleport:spawn") || msg.startsWith("/advancedteleport:spawn ")
                || msg.equals("/advancedteleport:back")) {
            // Small delay so AT can start the warmup first
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) startActionBarCountdown(player);
            }, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!activeCountdowns.containsKey(player.getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        cancelCountdown(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        cancelCountdown(player);
    }

    private void cancelCountdown(Player player) {
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) {
            t.cancel();
            player.sendActionBar(Component.empty());
        }
    }

    // ---------------------------------------------------------------------
    //  Death: disable tpauto, cancel countdown
    // ---------------------------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Cancel countdown
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) t.cancel();

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

        inv.setItem(10, settingButton(!noConfirmGui.contains(id), "Confirm GUIs",
                List.of(ChatColor.GRAY + "Show a confirmation GUI before",
                        ChatColor.GRAY + "sending or accepting requests.",
                        "", ChatColor.YELLOW + "Currently: " + status(!noConfirmGui.contains(id))),
                "toggle_confirm"));

        inv.setItem(13, settingButton(autoAccept.contains(id), "TPAuto",
                List.of(ChatColor.GRAY + "Automatically accept all",
                        ChatColor.GRAY + "incoming TPA requests.",
                        "", ChatColor.YELLOW + "Currently: " + status(autoAccept.contains(id))),
                "toggle_tpauto"));

        inv.setItem(15, settingButton(!blockTpa.contains(id), "Receive TPA",
                List.of(ChatColor.GRAY + "Allow others to send you",
                        ChatColor.GRAY + "TPA requests.",
                        "", ChatColor.YELLOW + "Currently: " + status(!blockTpa.contains(id))),
                "toggle_tpa"));

        inv.setItem(16, settingButton(!blockTpahere.contains(id), "Receive TPAHere",
                List.of(ChatColor.GRAY + "Allow others to send you",
                        ChatColor.GRAY + "TPAHere requests.",
                        "", ChatColor.YELLOW + "Currently: " + status(!blockTpahere.contains(id))),
                "toggle_tpahere"));

        player.openInventory(inv);
    }

    private String status(boolean on) {
        return on ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
    }

    private ItemStack settingButton(boolean on, String name, List<String> lore, String action) {
        Material mat = on ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        return button(mat, (on ? ChatColor.GREEN : ChatColor.RED) + name, lore, action);
    }

    private void refreshSettings(Player player) {
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
                "[close];advancedteleport:" + baseCmd + " " + target + ";start_countdown"));
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
                if (noConfirmGui.contains(id)) noConfirmGui.remove(id);
                else noConfirmGui.add(id);
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
                if (blockTpa.contains(id)) blockTpa.remove(id);
                else blockTpa.add(id);
                refreshSettings(player);
            }
            case "toggle_tpahere" -> {
                if (blockTpahere.contains(id)) blockTpahere.remove(id);
                else blockTpahere.add(id);
                refreshSettings(player);
            }
            default -> {
                for (String part : action.split(";")) {
                    String cmd = part.trim();
                    if (cmd.isEmpty()) continue;
                    if (cmd.equalsIgnoreCase("[close]")) {
                        player.closeInventory();
                    } else if (cmd.equalsIgnoreCase("start_countdown")) {
                        Bukkit.getScheduler().runTaskLater(ATConfirmGUI.this, () -> {
                            if (player.isOnline()) startActionBarCountdown(player);
                        }, 1L);
                    } else {
                        player.performCommand(cmd);
                    }
                }
            }
        }
    }
}
