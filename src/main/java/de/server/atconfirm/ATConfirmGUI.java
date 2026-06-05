package de.server.atconfirm;

import com.google.common.collect.ImmutableMap;
import io.github.niestrat99.advancedteleport.api.ATPlayer;
import io.github.niestrat99.advancedteleport.api.Home;
import io.github.niestrat99.advancedteleport.api.TeleportRequestType;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportAcceptEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportRequestEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ATConfirmGUI extends JavaPlugin implements Listener {

    private NamespacedKey actionKey;

    // ---- Per-player settings ----
    private final Set<UUID> autoAccept   = new HashSet<>();
    private final Set<UUID> noConfirmGui = new HashSet<>();
    private final Set<UUID> blockTpa     = new HashSet<>();
    private final Set<UUID> blockTpahere = new HashSet<>();

    // ---- Active visual countdown tasks ----
    private final Map<UUID, BukkitTask> activeCountdowns = new HashMap<>();
    // ---- Per-player tpauto action-bar refresher (so toggling can't stack tasks) ----
    private final Map<UUID, BukkitTask> autoBarTasks = new HashMap<>();
    // Remembers which cooldown key a player's active countdown belongs to, so a
    // cancelled teleport can clear that cooldown and allow an immediate retry.
    private final Map<UUID, String> countdownCooldownKey = new HashMap<>();

    // ---- Cooldown tracking (per player per command) ----
    private final Map<String, Long> lastCommandUse = new HashMap<>();

    // Loaded from config.yml (defaults: warmup 5s). MUST match AT's
    // warm-up-timer-duration so the visual lines up with the real teleport.
    private int warmupSeconds = 5;
    private long cooldownMs = 10_000L;
    private boolean blockInCombat = true;

    // CombatLogX integration via reflection (no hard dependency).
    private Object combatLogXPlugin;       // ICombatLogX instance
    private Method combatManagerGetter;    // ICombatLogX#getCombatManager()
    private Method isInCombatMethod;       // ICombatManager#isInCombat(Player)

    // Maximum home slots shown in the GUI (visual cap requested by server).
    private static final int MAX_HOME_SLOTS = 6;

    /** Marker holder so we can identify inventories created by this plugin. */
    private static final class GuiHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private Inventory createGui(Player player, int size, String title) {
        return Bukkit.createInventory(new GuiHolder(), size, title);
    }

    private boolean isOurGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof GuiHolder;
    }

    /** True if the player currently has one of our plugin GUIs open. */
    private boolean hasOurGuiOpen(Player player) {
        try {
            return isOurGui(player.getOpenInventory().getTopInventory());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("AdvancedTeleport") == null) {
            getLogger().severe("AdvancedTeleport not found! Disabling ATConfirmGUI.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        actionKey = new NamespacedKey(this, "action");
        saveDefaultConfig();
        warmupSeconds = Math.max(1, getConfig().getInt("warmup-seconds", 5));
        cooldownMs = Math.max(0L, getConfig().getLong("cooldown-seconds", 10)) * 1000L;
        blockInCombat = getConfig().getBoolean("block-teleport-in-combat", true);
        setupCombatLogX();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ATConfirmGUI enabled.");
    }

    @Override
    public void onDisable() {
        activeCountdowns.values().forEach(BukkitTask::cancel);
        activeCountdowns.clear();
        autoBarTasks.values().forEach(BukkitTask::cancel);
        autoBarTasks.clear();
        HandlerList.unregisterAll((Listener) this);
    }

    // =====================================================================
    //  COMBATLOGX INTEGRATION (reflection, optional dependency)
    // =====================================================================

    private void setupCombatLogX() {
        if (!blockInCombat) return;
        try {
            org.bukkit.plugin.Plugin clx = Bukkit.getPluginManager().getPlugin("CombatLogX");
            if (clx == null) {
                getLogger().info("CombatLogX not found - combat teleport blocking disabled.");
                return;
            }
            combatLogXPlugin = clx;
            // ICombatLogX#getCombatManager()
            combatManagerGetter = clx.getClass().getMethod("getCombatManager");
            Object manager = combatManagerGetter.invoke(clx);
            // ICombatManager#isInCombat(Player)
            isInCombatMethod = manager.getClass().getMethod("isInCombat", Player.class);
            getLogger().info("Hooked into CombatLogX - teleports are blocked while in combat.");
        } catch (Throwable t) {
            // API shape changed or not available; fail safe = no blocking.
            combatLogXPlugin = null;
            combatManagerGetter = null;
            isInCombatMethod = null;
            getLogger().warning("Could not hook CombatLogX (" + t.getClass().getSimpleName()
                    + "); combat teleport blocking disabled.");
        }
    }

    /** True if CombatLogX reports the player is currently in combat. */
    private boolean isInCombat(Player player) {
        if (!blockInCombat || combatLogXPlugin == null
                || combatManagerGetter == null || isInCombatMethod == null) {
            return false;
        }
        try {
            Object manager = combatManagerGetter.invoke(combatLogXPlugin);
            Object result = isInCombatMethod.invoke(manager, player);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false; // never block teleports because of a reflection error
        }
    }

    /** Sends a "you're in combat" message and returns true if blocked. */
    private boolean deniedByCombat(Player player) {
        if (isInCombat(player)) {
            player.sendMessage(ChatColor.RED + "You can't teleport while in combat!");
            return true;
        }
        return false;
    }

    // =====================================================================
    //  AT EVENTS
    // =====================================================================

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
            final Player traveller = isHere ? receiver : sender;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!receiver.isOnline()) return;
                // Don't auto-accept (and teleport) if the travelling player is
                // combat-tagged; that would bypass the combat block.
                if (traveller != null && isInCombat(traveller)) {
                    receiver.sendMessage(ChatColor.RED + "A teleport request was not auto-accepted because someone is in combat.");
                    return;
                }
                receiver.performCommand("advancedteleport:tpaccept");
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAccept(TeleportAcceptEvent event) {
        Player receiver = event.getReceivingPlayer();
        Player sender   = event.getSendingPlayer();
        boolean isHere = event.getRequestType() == TeleportRequestType.TPAHERE;
        Player whoTeleports = isHere ? receiver : sender;
        if (whoTeleports == null) return;
        startCountdown(whoTeleports);
    }

    // =====================================================================
    //  COMMAND PRE-PROCESS  (home / spawn / back — NOT rtp/tpr)
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().toLowerCase().trim();

        String cmdName = null;
        if (matches(msg, "home") || matches(msg, "advancedteleport:home")) {
            cmdName = "home";
        } else if (matches(msg, "spawn") || matches(msg, "advancedteleport:spawn")) {
            cmdName = "spawn";
        } else if (equalsCmd(msg, "back") || equalsCmd(msg, "advancedteleport:back")) {
            cmdName = "back";
        }
        if (cmdName == null) return;

        if (deniedByCombat(player)) { event.setCancelled(true); return; }
        if (isOnCooldown(player, cmdName)) return;
        markUsed(player, cmdName);

        final String cdKey = player.getUniqueId() + ":" + cmdName;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) startCountdown(player, cdKey);
        }, 1L);
    }

    private boolean matches(String msg, String cmd) {
        return msg.equals("/" + cmd) || msg.startsWith("/" + cmd + " ");
    }

    private boolean equalsCmd(String msg, String cmd) {
        return msg.equals("/" + cmd);
    }

    private boolean isOnCooldown(Player player, String cmd) {
        Long last = lastCommandUse.get(player.getUniqueId() + ":" + cmd);
        return last != null && (System.currentTimeMillis() - last) < cooldownMs;
    }

    private void markUsed(Player player, String cmd) {
        lastCommandUse.put(player.getUniqueId() + ":" + cmd, System.currentTimeMillis());
    }

    // =====================================================================
    //  COUNTDOWN (re-sent every 2 ticks so AT cannot overwrite it)
    // =====================================================================

    private void startCountdown(Player player) {
        startCountdown(player, null);
    }

    private void startCountdown(Player player, String cooldownKey) {
        cancelCountdownSilently(player);
        if (cooldownKey != null) {
            countdownCooldownKey.put(player.getUniqueId(), cooldownKey);
        } else {
            countdownCooldownKey.remove(player.getUniqueId());
        }

        // Drive the countdown by REAL wall-clock time, not by counting ticks.
        // Counting ticks drifts under server lag (20 ticks != 1s when TPS drops),
        // which made the timer run in slow-motion and teleport "early" at 3s.
        final long startMs = System.currentTimeMillis();
        final long totalMs = warmupSeconds * 1000L;
        // lastShown = the second value we last displayed; -1 forces an initial draw.
        final int[] lastShown = {-1};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                BukkitTask t = activeCountdowns.remove(player.getUniqueId());
                if (t != null) t.cancel();
                return;
            }

            long elapsed = System.currentTimeMillis() - startMs;
            long remainMs = totalMs - elapsed;

            // Seconds remaining, rounded up: 5000..4001ms -> 5, 4000..3001 -> 4, ...
            // If wall-clock time is up but AT hasn't fired the teleport yet
            // (server lag stretched AT's warmup), hold the display at 1s instead
            // of clearing early. onTeleport() will clear it the moment AT moves us,
            // and a hard safety stop below prevents a stuck bar.
            int secondsLeft = (int) ((remainMs + 999) / 1000);
            if (secondsLeft < 1) secondsLeft = 1;

            // Hard safety: if we're far past the expected time (e.g. teleport was
            // silently cancelled by another plugin and no event reached us), stop.
            if (elapsed > totalMs + 8000L) {
                player.sendActionBar(Component.empty());
                BukkitTask t = activeCountdowns.remove(player.getUniqueId());
                if (t != null) t.cancel();
                return;
            }

            // Refresh the action bar every run (every 2 ticks) so AT/other plugins
            // can't overwrite it, but only play the sound when the second changes.
            player.sendActionBar(Component.text(
                    "\u23F1 Teleporting in " + secondsLeft + "s... do not move!",
                    NamedTextColor.AQUA));

            if (secondsLeft != lastShown[0]) {
                lastShown[0] = secondsLeft;
                float pitch = secondsLeft == 1 ? 2.0f : 1.0f;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
            }
        }, 0L, 2L);

        activeCountdowns.put(player.getUniqueId(), task);
    }

    private void cancelCountdown(Player player) {
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) {
            t.cancel();
            // The teleport was aborted, so AT will NOT put the command on cooldown.
            // Clear our matching cooldown entry too, otherwise the next attempt
            // would teleport with no countdown/sound.
            String key = countdownCooldownKey.remove(player.getUniqueId());
            if (key != null) lastCommandUse.remove(key);
            sendActionBar(player, Component.text("\u2717 Teleport cancelled!", NamedTextColor.RED), 40L);
        }
    }

    private void cancelCountdownSilently(Player player) {
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) t.cancel();
        countdownCooldownKey.remove(player.getUniqueId());
    }

    // =====================================================================
    //  CANCEL TRIGGERS
    // =====================================================================

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!activeCountdowns.containsKey(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        cancelCountdown(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (activeCountdowns.containsKey(player.getUniqueId())) {
            cancelCountdown(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) {
            t.cancel();
            player.sendActionBar(Component.empty());
        }
        // Teleport succeeded; keep the cooldown entry (mirrors AT's own cooldown)
        // but stop tracking the key.
        countdownCooldownKey.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelCountdownSilently(player);
        stopAutoBar(player);
        lastCommandUse.keySet().removeIf(k -> k.startsWith(player.getUniqueId().toString() + ":"));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        cancelCountdownSilently(player);
        if (autoAccept.remove(player.getUniqueId())) {
            stopAutoBar(player);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline())
                    sendActionBar(player, Component.text("tpauto was disabled on death", NamedTextColor.RED), 40L);
            }, 1L);
        }
    }

    // =====================================================================
    //  COMMANDS
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "tpa" -> {
                if (args.length < 1) { player.sendMessage(ChatColor.RED + "Usage: /tpa <player>"); return true; }
                if (deniedByCombat(player)) return true;
                if (noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpa " + args[0]);
                } else {
                    openSendConfirm(player, args[0], "tpa");
                }
                return true;
            }
            case "tpahere" -> {
                if (args.length < 1) { player.sendMessage(ChatColor.RED + "Usage: /tpahere <player>"); return true; }
                if (deniedByCombat(player)) return true;
                if (noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpahere " + args[0]);
                } else {
                    openSendConfirm(player, args[0], "tpahere");
                }
                return true;
            }
            case "tpaccept" -> {
                if (deniedByCombat(player)) return true;
                if (autoAccept.contains(player.getUniqueId()) || noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpaccept");
                } else {
                    openAcceptConfirm(player);
                }
                return true;
            }
            case "tpauto" -> {
                if (autoAccept.contains(player.getUniqueId())) {
                    autoAccept.remove(player.getUniqueId());
                    stopAutoBar(player);
                    sendActionBar(player, Component.text("You disabled tpauto", NamedTextColor.RED), 40L);
                } else {
                    autoAccept.add(player.getUniqueId());
                    sendActionBarPermanent(player, Component.text("\u2714 tpauto enabled", NamedTextColor.GREEN));
                }
                return true;
            }
            case "tpsettings" -> {
                openSettings(player);
                return true;
            }
            case "rtp" -> {
                openRtpConfirm(player);
                return true;
            }
            case "homes" -> {
                openHomesGui(player);
                return true;
            }
            default -> { return false; }
        }
    }

    // =====================================================================
    //  HOMES GUI
    // =====================================================================

    private void openHomesGui(Player player) {
        ATPlayer atp = ATPlayer.getPlayer(player);
        if (atp == null) {
            player.sendMessage(ChatColor.RED + "Your player data isn't loaded yet, try again in a moment.");
            return;
        }

        Inventory inv = createGui(player, 27, ChatColor.DARK_GRAY + "Your Homes");

        ImmutableMap<String, Home> homes = atp.getHomes();
        List<String> homeNames = homes == null ? List.of() : homes.keySet().asList();

        // Normalise AT's home limit. Some setups return <= 0 for "unlimited"
        // (or a number larger than our slot count); in all those cases the GUI
        // should simply offer all MAX_HOME_SLOTS empty slots.
        int rawLimit = atp.getHomesLimit();
        int limit = (rawLimit <= 0 || rawLimit > MAX_HOME_SLOTS) ? MAX_HOME_SLOTS : rawLimit;

        // Slots used for the six home positions (centered-ish, two rows).
        int[] slots = {10, 11, 12, 14, 15, 16};

        // BUG 47 guard: a player may already have MORE homes than we have slots
        // (e.g. set before a limit change, or via admin perms). Those extra homes
        // would be invisible/undeletable here, so warn instead of hiding silently.
        boolean hasHiddenHomes = homeNames.size() > MAX_HOME_SLOTS;

        for (int i = 0; i < MAX_HOME_SLOTS; i++) {
            int slot = slots[i];
            if (i < homeNames.size()) {
                // Existing home
                String name = homeNames.get(i);
                Home home = homes.get(name);
                String coords = "";
                if (home != null && home.getLocation() != null) {
                    Location l = home.getLocation();
                    coords = ChatColor.GRAY + "World: " + ChatColor.WHITE
                            + (l.getWorld() != null ? l.getWorld().getName() : "?")
                            + ChatColor.GRAY + "  (" + ChatColor.WHITE
                            + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ChatColor.GRAY + ")";
                }
                inv.setItem(slot, button(Material.LIME_BED,
                        ChatColor.GREEN + "" + ChatColor.BOLD + name,
                        List.of(
                                coords,
                                "",
                                ChatColor.YELLOW + "Left-click " + ChatColor.GRAY + "to teleport",
                                ChatColor.RED + "Shift-click " + ChatColor.GRAY + "to delete"),
                        "home_tp:" + name));
            } else if (i < limit) {
                // Empty, available slot
                inv.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        ChatColor.AQUA + "Empty home slot",
                        List.of(
                                ChatColor.GRAY + "You can set a home here.",
                                "",
                                ChatColor.YELLOW + "Click " + ChatColor.GRAY + "to set a home"
                                        + ChatColor.DARK_GRAY + " at your location"),
                        "home_set"));
            } else {
                // Locked slot (beyond this player's limit)
                inv.setItem(slot, button(Material.RED_STAINED_GLASS_PANE,
                        ChatColor.RED + "Locked slot",
                        List.of(ChatColor.GRAY + "You can't set more homes."),
                        "noop"));
            }
        }

        // Info book in the corner. Show the real total; if the player somehow has
        // more homes than slots, tell them how to manage the extra ones.
        String usedLine = ChatColor.GRAY + "Used: " + ChatColor.WHITE + homeNames.size()
                + ChatColor.GRAY + " / " + ChatColor.WHITE + (rawLimit <= 0 ? "∞" : String.valueOf(limit));
        java.util.List<String> infoLore = new java.util.ArrayList<>();
        infoLore.add(usedLine);
        if (hasHiddenHomes) {
            infoLore.add(ChatColor.YELLOW + "Showing first " + MAX_HOME_SLOTS + " of " + homeNames.size() + " homes.");
            infoLore.add(ChatColor.GRAY + "Use /delhome <name> for the rest.");
        } else {
            infoLore.add(ChatColor.GRAY + "Set a home, teleport, or delete");
            infoLore.add(ChatColor.GRAY + "directly from this menu.");
        }
        inv.setItem(26, button(Material.BOOK, ChatColor.AQUA + "Homes", infoLore, "noop"));

        player.openInventory(inv);
    }

    /** Auto-generate the next free home name: home1, home2, ... within the cap. */
    private String nextHomeName(ATPlayer atp) {
        ImmutableMap<String, Home> homes = atp.getHomes();
        Set<String> existing = new HashSet<>();
        if (homes != null) {
            for (String k : homes.keySet()) existing.add(k.toLowerCase());
        }
        for (int i = 1; i <= MAX_HOME_SLOTS; i++) {
            String candidate = "home" + i;
            if (!existing.contains(candidate)) return candidate;
        }
        return null;
    }

    private void openDeleteConfirm(Player player, String homeName) {
        Inventory inv = createGui(player, 27,
                ChatColor.DARK_GRAY + "Delete \"" + homeName + "\"?");
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.GREEN + "Confirm delete",
                List.of(ChatColor.GRAY + "Permanently delete this home."),
                "home_delete_confirm:" + homeName));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                ChatColor.RED + "Cancel",
                List.of(ChatColor.GRAY + "Keep this home."),
                "home_back"));
        player.openInventory(inv);
    }

    // =====================================================================
    //  RTP CONFIRM GUI (own inventory so the click reaches this plugin)
    // =====================================================================

    private void openRtpConfirm(Player player) {
        Inventory inv = createGui(player, 27, ChatColor.DARK_GRAY + "Random Teleport");
        inv.setItem(13, button(Material.GRASS_BLOCK,
                ChatColor.GREEN + "" + ChatColor.BOLD + "Random Teleport",
                List.of(
                        ChatColor.GRAY + "Teleport to a random location.",
                        "",
                        ChatColor.YELLOW + "Click " + ChatColor.GRAY + "to start"),
                "[close];start_countdown;advancedteleport:tpr"));
        player.openInventory(inv);
    }

    // =====================================================================
    //  SETTINGS GUI
    // =====================================================================

    private void openSettings(Player player) {
        Inventory inv = createGui(player, 27, ChatColor.DARK_GRAY + "Teleport Settings");
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

    // =====================================================================
    //  ACTION BAR HELPERS
    // =====================================================================

    private void sendActionBar(Player player, Component message, long durationTicks) {
        player.sendActionBar(message);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !activeCountdowns.containsKey(player.getUniqueId())) {
                player.sendActionBar(Component.empty());
            }
        }, durationTicks);
    }

    private void sendActionBarPermanent(Player player, Component message) {
        // Cancel any previous tpauto refresher so rapid toggling can't stack tasks.
        BukkitTask old = autoBarTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();

        player.sendActionBar(message);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, t -> {
            if (!player.isOnline() || !autoAccept.contains(player.getUniqueId())) {
                t.cancel();
                autoBarTasks.remove(player.getUniqueId());
                if (player.isOnline() && !activeCountdowns.containsKey(player.getUniqueId())) {
                    player.sendActionBar(Component.empty());
                }
                return;
            }
            if (!activeCountdowns.containsKey(player.getUniqueId())) {
                player.sendActionBar(message);
            }
        }, 40L, 40L);
        autoBarTasks.put(player.getUniqueId(), task);
    }

    /** Stops the tpauto action-bar refresher for a player, if running. */
    private void stopAutoBar(Player player) {
        BukkitTask t = autoBarTasks.remove(player.getUniqueId());
        if (t != null) t.cancel();
    }

    // =====================================================================
    //  TPA CONFIRM GUIs
    // =====================================================================

    private void openSendConfirm(Player player, String target, String baseCmd) {
        String pretty = baseCmd.equals("tpahere") ? "TPA-Here" : "TPA";
        Inventory inv = createGui(player, 27,
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

    private void openAcceptConfirm(Player player) {
        Inventory inv = createGui(player, 27, ChatColor.DARK_GRAY + "Accept request?");
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

    // =====================================================================
    //  CLICK HANDLING
    // =====================================================================

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isOurGui(event.getInventory())) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Only react to our own GUIs, but when it IS ours, lock the whole window
        // (top + player inventory) so items can't be shift-clicked in or out.
        if (!isOurGui(event.getInventory())) return;
        event.setCancelled(true);

        // Act only on clicks inside the top (our) inventory carrying an action.
        if (event.getClickedInventory() == null || !isOurGui(event.getClickedInventory())) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        UUID id = player.getUniqueId();

        // ---- Homes GUI actions ----
        if (action.startsWith("home_tp:")) {
            String name = action.substring("home_tp:".length());
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                openDeleteConfirm(player, name);
            } else {
                player.closeInventory();
                if (deniedByCombat(player)) return;
                // Cooldown shared with /home so spamming can't double-fire.
                if (!isOnCooldown(player, "home")) {
                    markUsed(player, "home");
                    player.performCommand("advancedteleport:home " + name);
                    final String cdKey = player.getUniqueId() + ":home";
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline()) startCountdown(player, cdKey);
                    }, 1L);
                }
            }
            return;
        }
        if (action.equals("home_set")) {
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp == null) { player.closeInventory(); return; }
            if (!atp.canSetMoreHomes()) {
                player.sendMessage(ChatColor.RED + "You can't set any more homes.");
                player.closeInventory();
                return;
            }
            String name = nextHomeName(atp);
            if (name == null) {
                player.sendMessage(ChatColor.RED + "All home slots are in use.");
                return;
            }
            atp.addHome(name, player.getLocation(), player);
            player.sendMessage(ChatColor.GREEN + "Home \"" + name + "\" set at your location.");
            // Refresh the GUI after AT stores the home.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && hasOurGuiOpen(player)) openHomesGui(player);
            }, 10L);
            return;
        }
        if (action.equals("home_back")) {
            openHomesGui(player);
            return;
        }
        if (action.startsWith("home_delete_confirm:")) {
            String name = action.substring("home_delete_confirm:".length());
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp != null) {
                atp.removeHome(name, player);
                player.sendMessage(ChatColor.RED + "Home \"" + name + "\" deleted.");
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && hasOurGuiOpen(player)) openHomesGui(player);
            }, 10L);
            return;
        }
        if (action.equals("noop")) {
            return;
        }

        // ---- Settings toggles ----
        switch (action) {
            case "toggle_confirm" -> {
                if (noConfirmGui.contains(id)) noConfirmGui.remove(id);
                else noConfirmGui.add(id);
                refreshSettings(player);
                return;
            }
            case "toggle_tpauto" -> {
                if (autoAccept.contains(id)) {
                    autoAccept.remove(id);
                    stopAutoBar(player);
                    sendActionBar(player, Component.text("You disabled tpauto", NamedTextColor.RED), 40L);
                } else {
                    autoAccept.add(id);
                    sendActionBarPermanent(player, Component.text("\u2714 tpauto enabled", NamedTextColor.GREEN));
                }
                refreshSettings(player);
                return;
            }
            case "toggle_tpa" -> {
                if (blockTpa.contains(id)) blockTpa.remove(id);
                else blockTpa.add(id);
                refreshSettings(player);
                return;
            }
            case "toggle_tpahere" -> {
                if (blockTpahere.contains(id)) blockTpahere.remove(id);
                else blockTpahere.add(id);
                refreshSettings(player);
                return;
            }
            default -> { /* fall through to command list */ }
        }

        // ---- Generic command list (confirm buttons / rtp) ----
        for (String part : action.split(";")) {
            String cmd = part.trim();
            if (cmd.isEmpty()) continue;
            if (cmd.equalsIgnoreCase("[close]")) {
                player.closeInventory();
            } else if (cmd.equalsIgnoreCase("start_countdown")) {
                if (deniedByCombat(player)) { player.closeInventory(); return; }
                if (!isOnCooldown(player, "rtp")) {
                    markUsed(player, "rtp");
                    final String cdKey = player.getUniqueId() + ":rtp";
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline()) startCountdown(player, cdKey);
                    }, 1L);
                }
            } else {
                player.performCommand(cmd);
            }
        }
    }
}
