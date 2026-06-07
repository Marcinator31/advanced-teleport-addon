package de.server.atconfirm;

import com.google.common.collect.ImmutableMap;
import io.github.niestrat99.advancedteleport.api.ATPlayer;
import io.github.niestrat99.advancedteleport.api.Home;
import io.github.niestrat99.advancedteleport.api.Warp;
import io.github.niestrat99.advancedteleport.api.AdvancedTeleportAPI;
import io.github.niestrat99.advancedteleport.api.TeleportRequestType;
import io.github.niestrat99.advancedteleport.api.events.ATTeleportEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportAcceptEvent;
import io.github.niestrat99.advancedteleport.api.events.players.TeleportRequestEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
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

import java.io.File;
import java.lang.reflect.Method;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    // ---- Players we're waiting on to type a home name in chat ----
    // Value is "" for a brand-new home, or the existing home name when renaming.
    // ConcurrentHashMap because it's touched from the async chat event too.
    private final Map<UUID, String> awaitingHomeName = new ConcurrentHashMap<>();


    // Loaded from config.yml (defaults: warmup 5s). MUST match AT's
    // warm-up-timer-duration so the visual lines up with the real teleport.
    private int warmupSeconds = 5;
    private boolean blockInCombat = true;
    // Mirror AT's warmup-cancel settings so our countdown ends exactly when AT's
    // teleport does (read from AT's config on startup).
    private boolean cancelOnRotation = false;
    private boolean cancelOnMovement = true;
    // Server-wide RTP lock toggled by ops via /rtplock. Persisted in config.yml.
    private boolean rtpLocked = false;
    // Server-wide /back lock toggled by ops via /backlock. Persisted in config.yml.
    private boolean backLocked = false;
    // Server-wide warp lock toggled by ops via /warplock. Persisted in config.yml.
    private boolean warpLocked = false;
    // Whether our /afk command is active. Off by default to avoid clashing with
    // AFK plugins (EssentialsX etc.) that already register /afk.
    private boolean afkCommandEnabled = false;

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
        blockInCombat = getConfig().getBoolean("block-teleport-in-combat", true);
        rtpLocked = getConfig().getBoolean("rtp-locked", false);
        backLocked = getConfig().getBoolean("back-locked", false);
        warpLocked = getConfig().getBoolean("warp-locked", false);
        afkCommandEnabled = getConfig().getBoolean("afk-command-enabled", false);
        // Automatically match AT's warm-up-timer-duration if we can read it, so the
        // countdown always lines up with the real teleport without the server owner
        // keeping two values in sync. Our own config value is just a fallback.
        syncWarmupFromAdvancedTeleport();
        loadPlayerSettings();
        setupCombatLogX();
        Bukkit.getPluginManager().registerEvents(this, this);
        // If /afk is enabled, register it at runtime so it shows up in tab-complete.
        // We do this dynamically (instead of in plugin.yml) so that when it's
        // disabled the command isn't registered at all and can't clash with other
        // AFK plugins.
        if (afkCommandEnabled) registerAfkCommand();
        // Auto-configure AT's messages so our hotbar countdown + confirm GUIs work
        // without the server owner editing any files (plug & play for Modrinth).
        Bukkit.getScheduler().runTaskLater(this, this::autoConfigureAdvancedTeleport, 20L);
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

    /**
     * Adjusts AdvancedTeleport's custom-messages.yml so our own hotbar countdown
     * and confirm GUIs take over, then reloads AT. Runs once on startup and only
     * rewrites the file if something actually changed, so it's safe to repeat.
     */
    // =====================================================================
    //  PLAYER SETTINGS PERSISTENCE  (survives restarts)
    // =====================================================================

    private File playerSettingsFile() {
        return new File(getDataFolder(), "players.yml");
    }

    private void loadPlayerSettings() {
        try {
            File f = playerSettingsFile();
            if (!f.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            readUuidList(y, "autoAccept", autoAccept);
            readUuidList(y, "noConfirmGui", noConfirmGui);
            readUuidList(y, "blockTpa", blockTpa);
            readUuidList(y, "blockTpahere", blockTpahere);
        } catch (Throwable t) {
            getLogger().warning("Could not load players.yml: " + t.getMessage());
        }
    }

    private void readUuidList(YamlConfiguration y, String key, Set<UUID> target) {
        for (String s : y.getStringList(key)) {
            try { target.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePlayerSettings() {
        // Snapshot on the main thread, then write to disk off-thread so rapid
        // settings toggles never block the server with synchronous file I/O.
        final java.util.List<String> auto = toStringList(autoAccept);
        final java.util.List<String> noGui = toStringList(noConfirmGui);
        final java.util.List<String> bTpa = toStringList(blockTpa);
        final java.util.List<String> bHere = toStringList(blockTpahere);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                YamlConfiguration y = new YamlConfiguration();
                y.set("autoAccept", auto);
                y.set("noConfirmGui", noGui);
                y.set("blockTpa", bTpa);
                y.set("blockTpahere", bHere);
                y.save(playerSettingsFile());
            } catch (Throwable t) {
                getLogger().warning("Could not save players.yml: " + t.getMessage());
            }
        });
    }

    private java.util.List<String> toStringList(Set<UUID> set) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (UUID u : set) out.add(u.toString());
        return out;
    }

    /**
     * Reads AdvancedTeleport's warm-up-timer-duration from its config.yml and
     * uses it as our countdown length, so the visual always matches AT's real
     * warmup. Falls back to our own config value if AT's can't be read.
     */
    /**
     * Registers /afk at runtime via the server CommandMap so it appears in
     * tab-completion. Only called when afk-command-enabled is true. Uses
     * reflection so we don't hard-depend on Paper's CommandMap accessor.
     */
    private void registerAfkCommand() {
        try {
            Method getCommandMap = Bukkit.getServer().getClass().getMethod("getCommandMap");
            Object commandMap = getCommandMap.invoke(Bukkit.getServer());
            org.bukkit.command.Command cmd = new org.bukkit.command.Command("afk") {
                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                    if (sender instanceof Player p) {
                        handleCommand(p, "afk", args);
                    }
                    return true;
                }
            };
            cmd.setDescription("Open the AFK teleport menu.");
            Method register = commandMap.getClass().getMethod("register", String.class, org.bukkit.command.Command.class);
            register.invoke(commandMap, "atconfirmgui", cmd);
            getLogger().info("Registered /afk command.");
        } catch (Throwable t) {
            getLogger().warning("Could not register /afk command: " + t.getMessage());
        }
    }

    private void syncWarmupFromAdvancedTeleport() {
        try {
            org.bukkit.plugin.Plugin at = Bukkit.getPluginManager().getPlugin("AdvancedTeleport");
            if (at == null) return;
            File atConfig = new File(at.getDataFolder(), "config.yml");
            if (!atConfig.exists()) return;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(atConfig);
            // Mirror AT's cancel settings so our countdown reacts the same way.
            cancelOnRotation = cfg.getBoolean("cancel-warm-up-on-rotation", false);
            cancelOnMovement = cfg.getBoolean("cancel-warm-up-on-movement", true);
            if (!cfg.contains("warm-up-timer-duration")) return;
            int atWarmup = cfg.getInt("warm-up-timer-duration", warmupSeconds);
            if (atWarmup >= 1) {
                if (atWarmup != warmupSeconds) {
                    getLogger().info("Matched countdown to AdvancedTeleport's warm-up-timer-duration: "
                            + atWarmup + "s.");
                }
                warmupSeconds = atWarmup;
            }
        } catch (Throwable t) {
            getLogger().warning("Could not read AdvancedTeleport's warmup duration: " + t.getMessage());
        }
    }

    private void autoConfigureAdvancedTeleport() {
        try {
            org.bukkit.plugin.Plugin at = Bukkit.getPluginManager().getPlugin("AdvancedTeleport");
            if (at == null) return;

            File atFolder = at.getDataFolder();
            File messages = new File(atFolder, "custom-messages.yml");
            if (!messages.exists()) {
                getLogger().info("AT custom-messages.yml not found yet; skipping auto-config.");
                return;
            }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(messages);
            boolean changed = false;

            // 1) Silence AT's own warmup/cancel hotbar + title messages so they
            //    don't fight our action-bar countdown. A single space keeps AT
            //    from sending its default text.
            String[] blankFields = {
                    "Teleport.eventBeforeTP",
                    "Teleport.eventTeleport",
                    "Teleport.eventMovement",
                    "Teleport.eventDamage"
            };
            for (String path : blankFields) {
                // Only touch fields that already exist, so we never inject keys
                // that this AT version doesn't use.
                if (cfg.contains(path) && !"".equals(cfg.get(path))) { cfg.set(path, ""); changed = true; }
            }
            // Title/subtitle need a single space, not empty (empty -> AT default).
            String[] spaceFields = {
                    "Teleport.eventBeforeTP_title.0",
                    "Teleport.eventBeforeTP_title.20",
                    "Teleport.eventBeforeTP_title.40",
                    "Teleport.eventBeforeTP_title.60",
                    "Teleport.eventBeforeTP_subtitle.0",
                    "Teleport.eventBeforeTP_subtitle.60",
                    "Teleport.eventMovement_title.0"
            };
            for (String path : spaceFields) {
                if (cfg.contains(path) && !" ".equals(cfg.get(path))) { cfg.set(path, " "); changed = true; }
            }

            // Route AT's chat [ACCEPT]/[DENY] buttons through OUR commands so the
            // confirm GUI and combat check apply to clicks too. AT's defaults use
            // /tpayes and /tpano (which bypass us); rewrite them to /tpaccept and
            // /tpdeny, which our interceptor catches.
            String[] buttonFields = {
                    "Info.tpaRequestReceived",
                    "Info.tpaRequestHere"
            };
            for (String path : buttonFields) {
                String val = cfg.getString(path);
                if (val == null) continue;
                String fixed = val
                        .replace("/tpayes <player>", "/tpaccept")
                        .replace("/tpayes", "/tpaccept")
                        .replace("/tpano <player>", "/tpdeny")
                        .replace("/tpano", "/tpdeny");
                if (!fixed.equals(val)) { cfg.set(path, fixed); changed = true; }
            }

            if (changed) {
                cfg.save(messages);
                getLogger().info("Adjusted AdvancedTeleport custom-messages.yml for ATConfirmGUI.");
                // Reload AT so the changes take effect immediately.
                Bukkit.getScheduler().runTaskLater(this, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "advancedteleport:at reload"), 20L);
            }
        } catch (Throwable t) {
            getLogger().warning("Could not auto-configure AdvancedTeleport: " + t.getMessage());
        }
    }

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
            player.sendMessage("\u00a7c" + "You can't teleport while in combat!");
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
            sender.sendMessage("\u00a7c" + receiver.getName() + " is not accepting TPAHere requests.");
            return;
        }
        if (!isHere && blockTpa.contains(receiver.getUniqueId())) {
            event.setCancelled(true);
            sender.sendMessage("\u00a7c" + receiver.getName() + " is not accepting TPA requests.");
            return;
        }

        if (autoAccept.contains(receiver.getUniqueId())) {
            final Player traveller = isHere ? receiver : sender;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!receiver.isOnline()) return;
                // Don't auto-accept (and teleport) if the travelling player is
                // combat-tagged; that would bypass the combat block.
                if (traveller != null && isInCombat(traveller)) {
                    receiver.sendMessage("\u00a7c" + "A teleport request was not auto-accepted because someone is in combat.");
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
        Player target = isHere ? sender : receiver;
        if (whoTeleports == null) return;
        // If the travelling player is combat-tagged, AT's teleport will be blocked
        // elsewhere; don't show a misleading countdown.
        if (isInCombat(whoTeleports)) return;
        String dest = (target != null) ? target.getName() : null;
        // TPAHere = dark aqua, TPA = blue, so the two are visually distinct.
        NamedTextColor color = isHere ? NamedTextColor.DARK_AQUA : NamedTextColor.BLUE;
        startCountdown(whoTeleports, dest, color);
    }

    // =====================================================================
    //  AT TELEPORT EVENT  (fires only when AT actually starts a teleport, i.e.
    //  AFTER its own cooldown/permission checks pass). This is the reliable
    //  trigger for the warmup countdown - no more guessing in preprocess, so a
    //  command rejected by AT's cooldown no longer shows a phantom timer.
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onATTeleport(ATTeleportEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        // Only show our countdown for the "move yourself" teleport types.
        // TPA/TPAHERE are already handled by onAccept; warps are instant.
        ATTeleportEvent.TeleportType type = event.getType();
        if (type == null) return;
        final String destination;
        final NamedTextColor color;
        switch (type) {
            case HOME -> {
                // Use the actual home name AT is sending them to, if available.
                String loc = event.getLocName();
                destination = (loc != null && !loc.isEmpty()) ? "home \"" + loc + "\"" : "your home";
                color = NamedTextColor.GREEN;
            }
            case SPAWN -> { destination = "spawn"; color = NamedTextColor.YELLOW; }
            case BACK -> { destination = "your previous location"; color = NamedTextColor.GOLD; }
            case TPR -> { destination = "a random location"; color = NamedTextColor.LIGHT_PURPLE; }
            case WARP -> {
                String loc = event.getLocName();
                destination = (loc != null && !loc.isEmpty()) ? "warp \"" + loc + "\"" : "a warp";
                color = NamedTextColor.AQUA;
            }
            default -> { return; } // TPA/TPAHERE handled in onAccept
        }
        // AT has accepted the teleport (cooldown passed). Start the visual
        // countdown on the next tick so it lines up with AT's warmup.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) startCountdown(player, destination, color);
        }, 1L);
    }

    // =====================================================================
    //  COUNTDOWN (re-sent every 2 ticks so AT cannot overwrite it)
    // =====================================================================

    private void startCountdown(Player player) {
        startCountdown(player, null, NamedTextColor.AQUA);
    }

    private void startCountdown(Player player, String destination) {
        startCountdown(player, destination, NamedTextColor.AQUA);
    }

    /**
     * Starts the warmup countdown over the hotbar.
     *
     * @param destination a short phrase like "spawn", "your home" or "a random
     *                    location"; if null a generic message is shown.
     * @param color       the colour of the countdown text (per teleport type).
     */
    private void startCountdown(Player player, String destination, NamedTextColor color) {
        cancelCountdownSilently(player);

        // Build the message prefix once. With a destination we say e.g.
        // "Teleporting to spawn in 5s...", otherwise the generic line.
        final String prefix = (destination == null || destination.isEmpty())
                ? "\u23F1 Teleporting in "
                : "\u23F1 Teleporting to " + destination + " in ";

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
            int secondsLeft = (int) ((remainMs + 999) / 1000);
            if (secondsLeft < 1) secondsLeft = 1;

            // Once the warmup time is up we briefly hold at "1s" to absorb minor
            // lag between our clock and AT's real teleport. onTeleport() clears
            // this the instant AT moves the player. But if AT never teleports
            // (e.g. it silently cancelled the teleport - no event reaches us),
            // we must NOT sit on screen: after a short grace period, clear it.
            if (elapsed > totalMs + 1500L) {
                player.sendActionBar(Component.empty());
                BukkitTask t = activeCountdowns.remove(player.getUniqueId());
                if (t != null) t.cancel();
                return;
            }

            // Refresh the action bar every run (every 2 ticks) so AT/other plugins
            // can't overwrite it, but only play the sound when the second changes.
            player.sendActionBar(Component.text(
                    prefix + secondsLeft + "s... do not move!",
                    color));

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
            sendActionBar(player, Component.text("\u2717 Teleport cancelled!", NamedTextColor.RED), 40L);
        }
    }

    private void cancelCountdownSilently(Player player) {
        BukkitTask t = activeCountdowns.remove(player.getUniqueId());
        if (t != null) t.cancel();
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

        boolean movedBlock = from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
        boolean rotated = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();

        // Cancel under the same conditions AT does, so our countdown disappears
        // exactly when AT aborts the teleport (e.g. when looking around if AT's
        // cancel-warm-up-on-rotation is enabled).
        if ((cancelOnMovement && movedBlock) || (cancelOnRotation && rotated)) {
            cancelCountdown(event.getPlayer());
        }
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
            // Play the ender-pearl arrival sound, but only for OUR countdown
            // teleports (t != null) - not for unrelated teleports like pearls or
            // admin /tp. Scheduled a tick later so it plays at the destination.
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.playSound(player.getLocation(),
                            Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelCountdownSilently(player);
        stopAutoBar(player);
        awaitingHomeName.remove(player.getUniqueId());
    }

    // =====================================================================
    //  HOME-NAME CHAT INPUT  (player typed a name after clicking an empty slot)
    // =====================================================================

    // =====================================================================
    //  COMMAND INTERCEPTOR  (so no commands.yml is needed - plug & play)
    //  We catch AT's own command labels and route them to our GUIs instead.
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandIntercept(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg.isEmpty() || msg.charAt(0) != '/') return;

        // Strip leading slash, split off the label and arguments.
        String body = msg.substring(1);
        String[] parts = body.trim().split("\\s+");
        if (parts.length == 0) return;

        String label = parts[0].toLowerCase();

        // Never touch explicitly-namespaced calls (e.g. advancedteleport:tpa).
        // Those are our own pass-through calls to AT and must reach AT directly.
        if (label.contains(":")) return;

        // Only intercept the labels we actually handle.
        switch (label) {
            case "tpa", "tpahere", "tpaccept", "tpauto", "tpsettings", "settings",
                 "rtp", "tpr", "menu", "tpmenu", "homes", "rtplock", "back", "backlock",
                 "warp", "warps", "warplock", "afk" -> {
                String[] args = new String[parts.length - 1];
                System.arraycopy(parts, 1, args, 0, args.length);

                // For /homes <player> we let AT handle it (view others' homes),
                // so only intercept the no-argument form. handleCommand() already
                // contains this logic, but we must not cancel AT's own command in
                // the with-args case, so check here too.
                if (label.equals("homes") && args.length > 0) {
                    return; // fall through to AT
                }
                // /afk is opt-in; if disabled, don't intercept it so any other
                // AFK plugin keeps working normally.
                if (label.equals("afk") && !afkCommandEnabled) {
                    return; // fall through to whatever else handles /afk
                }

                event.setCancelled(true);
                final Player player = event.getPlayer();
                // Run on the next tick to stay clear of the command pipeline.
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) handleCommand(player, label, args);
                });
            }
            default -> { /* not ours */ }
        }
    }

    /**
     * Clears a pending home-name prompt after 30s so a player who clicked an
     * empty slot but never typed a name doesn't have their next unrelated chat
     * message silently consumed as a home name.
     */
    private void scheduleNameInputTimeout(UUID uuid) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (awaitingHomeName.remove(uuid) != null) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendMessage("\u00a77" + "Home naming timed out.");
                }
            }
        }, 600L); // 30 seconds
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatName(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingHomeName.containsKey(player.getUniqueId())) return;

        // This message is for us, not public chat.
        event.setCancelled(true);
        final String oldName = awaitingHomeName.remove(player.getUniqueId());

        String raw = event.getMessage().trim();

        final boolean isRename = oldName != null && !oldName.isEmpty();

        if (raw.equalsIgnoreCase("cancel")) {
            player.sendMessage("\u00a77" + (isRename ? "Rename cancelled." : "Home creation cancelled."));
            return;
        }

        // AT home names are alphanumeric. Sanitise and validate.
        final String name = raw.replaceAll("\\s+", "_");
        if (!name.matches("[A-Za-z0-9_]{1,32}")) {
            player.sendMessage("\u00a7c" + "Invalid name. Use only letters, numbers and underscores (max 32).");
            return;
        }
        // "bed" is reserved by AT for the virtual bed-spawn home.
        if (name.equalsIgnoreCase("bed")) {
            player.sendMessage("\u00a7c" + "\"bed\" is reserved. Please choose another name.");
            return;
        }

        // Hop back onto the main thread for all Bukkit/AT calls.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp == null) {
                player.sendMessage("\u00a7c" + "Your player data isn't loaded, try again.");
                return;
            }
            if (atp.hasHome(name)) {
                player.sendMessage("\u00a7c" + "You already have a home called \"" + name + "\".");
                return;
            }

            if (isRename) {
                // AT has no direct rename. Recreate at the same spot under the new
                // name FIRST, then only remove the old one once the new one exists,
                // so a failure can never destroy the home without a replacement.
                Home old = atp.getHome(oldName);
                if (old == null || old.getLocation() == null) {
                    player.sendMessage("\u00a7c" + "That home no longer exists.");
                    return;
                }
                final Location loc = old.getLocation();
                atp.addHome(name, loc, player);
                // Verify the new home actually got created before deleting the old.
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!player.isOnline()) return;
                    ATPlayer atp2 = ATPlayer.getPlayer(player);
                    if (atp2 == null) return;
                    if (atp2.hasHome(name)) {
                        atp2.removeHome(oldName, player);
                        player.sendMessage("\u00a7a" + "Renamed \"" + oldName + "\" to \"" + name + "\".");
                    } else {
                        player.sendMessage("\u00a7c" + "Rename failed; your home \"" + oldName + "\" is unchanged.");
                    }
                    if (player.isOnline()) openHomesGui(player);
                }, 8L);
                return;
            } else {
                if (!atp.canSetMoreHomes()) {
                    player.sendMessage("\u00a7c" + "You can't set any more homes.");
                    return;
                }
                atp.addHome(name, player.getLocation(), player);
                player.sendMessage("\u00a7a" + "Home \"" + name + "\" set at your location.");
            }

            // Reopen the homes GUI so they see the change.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) openHomesGui(player);
            }, 5L);
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        cancelCountdownSilently(player);
        if (autoAccept.remove(player.getUniqueId())) {
            stopAutoBar(player);
            savePlayerSettings();
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
        return handleCommand(player, command.getName().toLowerCase(), args);
    }

    /**
     * Core command logic, shared by the plugin.yml command executor and the
     * command interceptor (so the plugin works with or without commands.yml).
     *
     * @return true if we handled it (caller should cancel/stop), false otherwise.
     */
    /**
     * Returns true (and notifies the player) if they lack the given AT permission.
     * Keeps players who can't use a feature from opening its GUI in the first
     * place. Ops always pass.
     */
    private boolean lacksPerm(Player player, String node) {
        if (player.isOp() || player.hasPermission(node)) return false;
        player.sendMessage("\u00a7c" + "You don't have permission to do that.");
        return true;
    }

    /**
     * Validates a player-name argument before it is ever placed into a command
     * action string. This blocks command injection via names containing ';' or
     * spaces. Allows the characters valid in Java/Bedrock names plumbed through
     * Geyser (letters, digits, underscore and a leading dot), max 16 chars.
     */
    private boolean isValidName(String s) {
        return s != null && s.matches("\\.?[A-Za-z0-9_]{1,16}");
    }

    private boolean handleCommand(Player player, String name, String[] args) {
        switch (name) {
            case "tpa" -> {
                if (args.length < 1) { player.sendMessage("\u00a7c" + "Usage: /tpa <player>"); return true; }
                if (lacksPerm(player, "at.member.tpa")) return true;
                if (!isValidName(args[0])) { player.sendMessage("\u00a7c" + "Invalid player name."); return true; }
                if (deniedByCombat(player)) return true;
                if (noConfirmGui.contains(player.getUniqueId())) {
                    player.performCommand("advancedteleport:tpa " + args[0]);
                } else {
                    openSendConfirm(player, args[0], "tpa");
                }
                return true;
            }
            case "tpahere" -> {
                if (args.length < 1) { player.sendMessage("\u00a7c" + "Usage: /tpahere <player>"); return true; }
                if (lacksPerm(player, "at.member.tpahere")) return true;
                if (!isValidName(args[0])) { player.sendMessage("\u00a7c" + "Invalid player name."); return true; }
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
                savePlayerSettings();
                return true;
            }
            case "tpsettings", "settings" -> {
                openSettings(player);
                return true;
            }
            case "rtp", "tpr" -> {
                if (lacksPerm(player, "at.member.tpr")) return true;
                if (rtpBlockedFor(player)) return true;
                openRtpConfirm(player);
                return true;
            }
            case "homes" -> {
                if (args.length == 0) {
                    if (lacksPerm(player, "at.member.homes")) return true;
                    // No arguments: open our own homes GUI for the player.
                    openHomesGui(player);
                } else {
                    // Arguments present (e.g. /homes <player>): this is AT's
                    // "view another player's homes" feature - hand it to AT.
                    StringBuilder sb = new StringBuilder("advancedteleport:homes");
                    for (String a : args) sb.append(' ').append(a);
                    player.performCommand(sb.toString());
                }
                return true;
            }
            case "menu", "tpmenu" -> {
                openMainMenu(player);
                return true;
            }
            case "rtplock" -> {
                if (!player.isOp() && !player.hasPermission("atconfirmgui.rtplock")) {
                    player.sendMessage("\u00a7c" + "You don't have permission to do that.");
                    return true;
                }
                openRtpLockGui(player);
                return true;
            }
            case "backlock" -> {
                if (!player.isOp() && !player.hasPermission("atconfirmgui.backlock")) {
                    player.sendMessage("\u00a7c" + "You don't have permission to do that.");
                    return true;
                }
                openBackLockGui(player);
                return true;
            }
            case "warps" -> {
                if (lacksPerm(player, "at.member.warps")) return true;
                openWarpsGui(player);
                return true;
            }
            case "warp" -> {
                if (args.length == 0) {
                    if (lacksPerm(player, "at.member.warps")) return true;
                    openWarpsGui(player);
                } else {
                    if (warpBlockedFor(player)) return true;
                    if (deniedByCombat(player)) return true;
                    if (!isValidName(args[0])) { player.sendMessage("\u00a7c" + "Invalid warp name."); return true; }
                    player.performCommand("advancedteleport:warp " + args[0]);
                }
                return true;
            }
            case "warplock" -> {
                if (!player.isOp() && !player.hasPermission("atconfirmgui.warplock")) {
                    player.sendMessage("\u00a7c" + "You don't have permission to do that.");
                    return true;
                }
                openWarpLockGui(player);
                return true;
            }
            case "afk" -> {
                // Disabled by default; only handle it if the server opted in.
                if (!afkCommandEnabled) return false;
                openAfkGui(player);
                return true;
            }
            case "back" -> {
                if (backBlockedFor(player)) return true;
                if (deniedByCombat(player)) return true;
                player.performCommand("advancedteleport:back");
                return true;
            }
            default -> { return false; }
        }
    }

    // =====================================================================
    //  HOMES GUI
    // =====================================================================

    // =====================================================================
    //  MAIN MENU HUB  (/menu)
    // =====================================================================

    private void openMainMenu(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Teleport Menu");

        inv.setItem(10, button(Material.LIME_BED,
                "\u00a7a\u00a7l" + "Homes",
                List.of("\u00a77" + "View, set and manage", "\u00a77" + "your homes."),
                "menu_homes"));

        // RTP - show as locked (red) for players who can't bypass the lock.
        boolean rtpBlocked = rtpLocked && !(player.isOp() || player.hasPermission("atconfirmgui.rtplock"));
        if (rtpBlocked) {
            inv.setItem(11, button(Material.RED_STAINED_GLASS_PANE,
                    "\u00a7c\u00a7l" + "Random Teleport",
                    List.of("\u00a7c" + "Currently locked by an admin."),
                    "menu_rtp_locked"));
        } else {
            inv.setItem(11, button(Material.GRASS_BLOCK,
                    "\u00a7d\u00a7l" + "Random Teleport",
                    List.of("\u00a77" + "Teleport to a random", "\u00a77" + "location in the wild."),
                    "menu_rtp"));
        }

        inv.setItem(12, button(Material.RED_BED,
                "\u00a7e\u00a7l" + "Spawn",
                List.of("\u00a77" + "Teleport to spawn."),
                "menu_spawn"));

        // Back - show as locked (red) for players who can't bypass the lock.
        boolean backBlocked = backLocked && !(player.isOp() || player.hasPermission("atconfirmgui.backlock"));
        if (backBlocked) {
            inv.setItem(13, button(Material.RED_STAINED_GLASS_PANE,
                    "\u00a7c\u00a7l" + "Back",
                    List.of("\u00a7c" + "Currently locked by an admin."),
                    "menu_back_locked"));
        } else {
            inv.setItem(13, button(Material.CLOCK,
                    "\u00a7b\u00a7l" + "Back",
                    List.of("\u00a77" + "Return to your previous", "\u00a77" + "location."),
                    "menu_back"));
        }

        // Warps browser.
        inv.setItem(14, button(Material.LODESTONE,
                "\u00a7b\u00a7l" + "Warps",
                List.of("\u00a77" + "Browse and teleport", "\u00a77" + "to server warps."),
                "menu_warps"));

        inv.setItem(15, button(Material.COMPARATOR,
                "\u00a7f\u00a7l" + "Settings",
                List.of("\u00a77" + "Toggle confirm GUIs,", "\u00a77" + "tpauto and request types."),
                "menu_settings"));

        boolean auto = autoAccept.contains(player.getUniqueId());
        inv.setItem(16, button(auto ? Material.LIME_DYE : Material.GRAY_DYE,
                (auto ? "\u00a7a" : "\u00a7c") + "\u00a7l" + "TPAuto: " + (auto ? "ON" : "OFF"),
                List.of("\u00a77" + "Auto-accept incoming", "\u00a77" + "teleport requests.",
                        "", "\u00a7e" + "Click " + "\u00a77" + "to toggle."),
                "menu_tpauto"));

        player.openInventory(inv);
    }

    private void openHomesGui(Player player) {
        ATPlayer atp = ATPlayer.getPlayer(player);
        if (atp == null) {
            player.sendMessage("\u00a7c" + "Your player data isn't loaded yet, try again in a moment.");
            return;
        }

        Inventory inv = createGui(player, 27, "\u00a78" + "Your Homes");

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
                // The "bed" entry is AT's virtual bed-spawn home (add-bed-to-homes),
                // not a real stored home, so it must not be deleted/renamed/moved.
                boolean isBed = name.equalsIgnoreCase("bed");
                String coords = "";
                if (home != null && home.getLocation() != null) {
                    Location l = home.getLocation();
                    coords = "\u00a77" + "World: " + "\u00a7f"
                            + (l.getWorld() != null ? l.getWorld().getName() : "?")
                            + "\u00a77" + "  (" + "\u00a7f"
                            + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + "\u00a77" + ")";
                }
                if (isBed) {
                    inv.setItem(slot, button(Material.LIME_BED,
                            "\u00a7d\u00a7l" + name + " \u00a77(bed)",
                            List.of(
                                    coords,
                                    "",
                                    "\u00a7e" + "Left-click " + "\u00a77" + "to teleport",
                                    "\u00a78" + "Your bed spawn - set in-game."),
                            "home_tp:" + name));
                } else {
                    inv.setItem(slot, button(Material.LIME_BED,
                            "\u00a7a\u00a7l" + name,
                            List.of(
                                    coords,
                                    "",
                                    "\u00a7e" + "Left-click " + "\u00a77" + "to teleport",
                                    "\u00a7b" + "Right-click " + "\u00a77" + "for options",
                                    "\u00a7c" + "Shift-click " + "\u00a77" + "to delete"),
                            "home_tp:" + name));
                }
            } else if (i < limit) {
                // Empty, available slot
                inv.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "\u00a7b" + "Empty home slot",
                        List.of(
                                "\u00a77" + "Set a home at your location",
                                "\u00a77" + "and give it your own name.",
                                "",
                                "\u00a7e" + "Click " + "\u00a77" + "to choose a name"),
                        "home_set"));
            } else {
                // Locked slot (beyond this player's limit)
                inv.setItem(slot, button(Material.RED_STAINED_GLASS_PANE,
                        "\u00a7c" + "Locked slot",
                        List.of("\u00a77" + "You can't set more homes."),
                        "noop"));
            }
        }

        // Info book in the corner. Show the real total; if the player somehow has
        // more homes than slots, tell them how to manage the extra ones.
        String usedLine = "\u00a77" + "Used: " + "\u00a7f" + homeNames.size()
                + "\u00a77" + " / " + "\u00a7f" + (rawLimit <= 0 ? "∞" : String.valueOf(limit));
        java.util.List<String> infoLore = new java.util.ArrayList<>();
        infoLore.add(usedLine);
        if (hasHiddenHomes) {
            infoLore.add("\u00a7e" + "Showing first " + MAX_HOME_SLOTS + " of " + homeNames.size() + " homes.");
            infoLore.add("\u00a77" + "Use /delhome <name> for the rest.");
        } else {
            infoLore.add("\u00a77" + "Set a home, teleport, or delete");
            infoLore.add("\u00a77" + "directly from this menu.");
        }
        inv.setItem(26, button(Material.BOOK, "\u00a7b" + "Homes", infoLore, "noop"));

        player.openInventory(inv);
    }

    private void openDeleteConfirm(Player player, String homeName) {
        Inventory inv = createGui(player, 27,
                "\u00a78" + "Delete \"" + homeName + "\"?");
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                "\u00a7a" + "Confirm delete",
                List.of("\u00a77" + "Permanently delete this home."),
                "home_delete_confirm:" + homeName));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                "\u00a7c" + "Cancel",
                List.of("\u00a77" + "Keep this home."),
                "home_back"));
        player.openInventory(inv);
    }

    /** Action menu for a single home: rename, move here, delete. */
    private void openHomeActions(Player player, String homeName) {
        Inventory inv = createGui(player, 27,
                "\u00a78" + "Home: " + homeName);
        inv.setItem(10, button(Material.NAME_TAG,
                "\u00a7b" + "Rename",
                List.of("\u00a77" + "Give this home a new name."),
                "home_rename:" + homeName));
        inv.setItem(12, button(Material.ENDER_PEARL,
                "\u00a7d" + "Move here",
                List.of("\u00a77" + "Move this home to your",
                        "\u00a77" + "current location."),
                "home_move:" + homeName));
        inv.setItem(14, button(Material.LIME_STAINED_GLASS_PANE,
                "\u00a7a" + "Teleport",
                List.of("\u00a77" + "Teleport to this home."),
                "home_tpfromactions:" + homeName));
        inv.setItem(16, button(Material.RED_STAINED_GLASS_PANE,
                "\u00a7c" + "Delete",
                List.of("\u00a77" + "Permanently delete this home."),
                "home_delete_confirm:" + homeName));
        inv.setItem(22, button(Material.BARRIER,
                "\u00a77" + "Back",
                List.of("\u00a78" + "Return to your homes."),
                "home_back"));
        player.openInventory(inv);
    }

    // =====================================================================
    //  RTP CONFIRM GUI (own inventory so the click reaches this plugin)
    // =====================================================================

    private void openRtpConfirm(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Random Teleport");
        inv.setItem(13, button(Material.GRASS_BLOCK,
                "\u00a7a\u00a7l" + "Random Teleport",
                List.of(
                        "\u00a77" + "Teleport to a random location.",
                        "",
                        "\u00a7e" + "Click " + "\u00a77" + "to start"),
                "[close];start_countdown;advancedteleport:tpr"));
        player.openInventory(inv);
    }

    // =====================================================================
    //  RTP LOCK  (op-only server-wide toggle via /rtplock)
    // =====================================================================

    /**
     * Returns true (and shows a hotbar message) if RTP is locked and the player
     * is not allowed to bypass it. Ops / permission holders are never blocked.
     */
    private boolean rtpBlockedFor(Player player) {
        if (!rtpLocked) return false;
        if (player.isOp() || player.hasPermission("atconfirmgui.rtplock")) return false;
        sendActionBar(player, Component.text("\u2717 RTP is blocked", NamedTextColor.RED), 60L);
        return true;
    }

    private void openRtpLockGui(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "RTP Lock");
        // Green pane = RTP enabled, red pane = RTP locked. Clicking toggles.
        if (rtpLocked) {
            inv.setItem(13, button(Material.RED_STAINED_GLASS_PANE,
                    "\u00a7c\u00a7l" + "RTP is LOCKED",
                    List.of("\u00a77" + "Players cannot use /rtp.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to unlock"),
                    "rtplock_toggle"));
        } else {
            inv.setItem(13, button(Material.LIME_STAINED_GLASS_PANE,
                    "\u00a7a\u00a7l" + "RTP is ENABLED",
                    List.of("\u00a77" + "Players can use /rtp.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to lock"),
                    "rtplock_toggle"));
        }
        player.openInventory(inv);
    }

    /**
     * Returns true (and shows a hotbar message) if /back is locked and the player
     * is not allowed to bypass it. Ops / permission holders are never blocked.
     */
    private boolean backBlockedFor(Player player) {
        if (!backLocked) return false;
        if (player.isOp() || player.hasPermission("atconfirmgui.backlock")) return false;
        sendActionBar(player, Component.text("\u2717 Back is blocked", NamedTextColor.RED), 60L);
        return true;
    }

    private void openBackLockGui(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Back Lock");
        // Green pane = /back enabled, red pane = /back locked. Clicking toggles.
        if (backLocked) {
            inv.setItem(13, button(Material.RED_STAINED_GLASS_PANE,
                    "\u00a7c\u00a7l" + "Back is LOCKED",
                    List.of("\u00a77" + "Players cannot use /back.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to unlock"),
                    "backlock_toggle"));
        } else {
            inv.setItem(13, button(Material.LIME_STAINED_GLASS_PANE,
                    "\u00a7a\u00a7l" + "Back is ENABLED",
                    List.of("\u00a77" + "Players can use /back.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to lock"),
                    "backlock_toggle"));
        }
        player.openInventory(inv);
    }

    private boolean warpBlockedFor(Player player) {
        if (!warpLocked) return false;
        if (player.isOp() || player.hasPermission("atconfirmgui.warplock")) return false;
        sendActionBar(player, Component.text("\u2717 Warps are blocked", NamedTextColor.RED), 60L);
        return true;
    }

    private void openWarpLockGui(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Warp Lock");
        if (warpLocked) {
            inv.setItem(13, button(Material.RED_STAINED_GLASS_PANE,
                    "\u00a7c\u00a7l" + "Warps are LOCKED",
                    List.of("\u00a77" + "Players cannot use /warp.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to unlock"),
                    "warplock_toggle"));
        } else {
            inv.setItem(13, button(Material.LIME_STAINED_GLASS_PANE,
                    "\u00a7a\u00a7l" + "Warps are ENABLED",
                    List.of("\u00a77" + "Players can use /warp.",
                            "", "\u00a7e" + "Click " + "\u00a77" + "to lock"),
                    "warplock_toggle"));
        }
        player.openInventory(inv);
    }

    /** Lists all server warps as clickable items. */
    private void openWarpsGui(Player player) {
        java.util.Map<String, Warp> warps;
        try {
            warps = AdvancedTeleportAPI.getWarps();
        } catch (Throwable t) {
            player.sendMessage("\u00a7c" + "Warps are not available.");
            return;
        }
        if (warps == null) warps = new HashMap<>();

        java.util.List<String> names = new java.util.ArrayList<>(warps.keySet());
        java.util.Collections.sort(names);

        // Size the GUI to fit all warps (multiple of 9, min 27, max 54).
        int rows = Math.max(3, Math.min(6, (int) Math.ceil((names.size() + 1) / 9.0)));
        Inventory inv = createGui(player, rows * 9, "\u00a78" + "Warps");

        int slot = 0;
        int maxSlots = rows * 9;
        for (String name : names) {
            if (slot >= maxSlots) break;
            Warp w = warps.get(name);
            String coords = "";
            if (w != null && w.getLocation() != null) {
                Location l = w.getLocation();
                coords = "\u00a77" + "World: " + "\u00a7f"
                        + (l.getWorld() != null ? l.getWorld().getName() : "?")
                        + "\u00a77" + "  (" + "\u00a7f"
                        + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + "\u00a77" + ")";
            }
            inv.setItem(slot, button(Material.LODESTONE,
                    "\u00a7b\u00a7l" + name,
                    List.of(coords, "", "\u00a7e" + "Click " + "\u00a77" + "to teleport"),
                    "warp_tp:" + name));
            slot++;
        }
        if (names.isEmpty()) {
            inv.setItem(maxSlots / 2, button(Material.BARRIER,
                    "\u00a7c" + "No warps set",
                    List.of("\u00a77" + "An admin can create warps", "\u00a77" + "with /setwarp."),
                    "noop"));
        }
        player.openInventory(inv);
    }

    /**
     * Confirmation GUI for /afk: teleports the player to the warp named "afk".
     * Shows a friendly notice if that warp doesn't exist.
     */
    private void openAfkGui(Player player) {
        boolean hasAfkWarp = false;
        try {
            java.util.Map<String, Warp> warps = AdvancedTeleportAPI.getWarps();
            if (warps != null) {
                for (String n : warps.keySet()) {
                    if (n.equalsIgnoreCase("afk")) { hasAfkWarp = true; break; }
                }
            }
        } catch (Throwable ignored) {}

        Inventory inv = createGui(player, 27, "\u00a78" + "AFK");
        if (hasAfkWarp) {
            inv.setItem(13, button(Material.GRASS_BLOCK,
                    "\u00a7a\u00a7l" + "Go AFK",
                    List.of(
                            "\u00a77" + "Teleport to the AFK area.",
                            "",
                            "\u00a7e" + "Click " + "\u00a77" + "to teleport"),
                    "warp_tp:afk"));
        } else {
            inv.setItem(13, button(Material.BARRIER,
                    "\u00a7c\u00a7l" + "No AFK area set",
                    List.of("\u00a77" + "An admin needs to create a warp",
                            "\u00a77" + "named \"afk\" with /setwarp afk."),
                    "noop"));
        }
        player.openInventory(inv);
    }

    // =====================================================================
    //  SETTINGS GUI
    // =====================================================================

    private void openSettings(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Teleport Settings");
        UUID id = player.getUniqueId();

        inv.setItem(10, settingButton(!noConfirmGui.contains(id), "Confirm GUIs",
                List.of("\u00a77" + "Show a confirmation GUI before",
                        "\u00a77" + "sending or accepting requests.",
                        "", "\u00a7e" + "Currently: " + status(!noConfirmGui.contains(id))),
                "toggle_confirm"));

        inv.setItem(13, settingButton(autoAccept.contains(id), "TPAuto",
                List.of("\u00a77" + "Automatically accept all",
                        "\u00a77" + "incoming TPA requests.",
                        "", "\u00a7e" + "Currently: " + status(autoAccept.contains(id))),
                "toggle_tpauto"));

        inv.setItem(15, settingButton(!blockTpa.contains(id), "Receive TPA",
                List.of("\u00a77" + "Allow others to send you",
                        "\u00a77" + "TPA requests.",
                        "", "\u00a7e" + "Currently: " + status(!blockTpa.contains(id))),
                "toggle_tpa"));

        inv.setItem(16, settingButton(!blockTpahere.contains(id), "Receive TPAHere",
                List.of("\u00a77" + "Allow others to send you",
                        "\u00a77" + "TPAHere requests.",
                        "", "\u00a7e" + "Currently: " + status(!blockTpahere.contains(id))),
                "toggle_tpahere"));

        player.openInventory(inv);
    }

    private String status(boolean on) {
        return on ? "\u00a7a" + "Enabled" : "\u00a7c" + "Disabled";
    }

    private ItemStack settingButton(boolean on, String name, List<String> lore, String action) {
        Material mat = on ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        return button(mat, (on ? "\u00a7a" : "\u00a7c") + name, lore, action);
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
        BukkitTask existing = autoBarTasks.remove(player.getUniqueId());
        if (existing != null) existing.cancel();

        player.sendActionBar(message);
        // Use Runnable overload (returns BukkitTask); cancel via the stored map entry.
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, (Runnable) () -> {
            if (!player.isOnline() || !autoAccept.contains(player.getUniqueId())) {
                BukkitTask self = autoBarTasks.remove(player.getUniqueId());
                if (self != null) self.cancel();
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
                "\u00a78" + "Send " + pretty + " to " + target + "?");
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                "\u00a7a" + "Confirm",
                List.of("\u00a77" + "Send the request to " + target + "."),
                "[close];advancedteleport:" + baseCmd + " " + target));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                "\u00a7c" + "Cancel",
                List.of("\u00a77" + "Do not send the request."),
                "[close]"));
        player.openInventory(inv);
    }

    private void openAcceptConfirm(Player player) {
        Inventory inv = createGui(player, 27, "\u00a78" + "Accept request?");
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                "\u00a7a" + "Accept",
                List.of("\u00a77" + "Accept the teleport request."),
                "[close];advancedteleport:tpaccept"));
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                "\u00a7c" + "Deny",
                List.of("\u00a77" + "Deny the teleport request."),
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
            boolean isBed = name.equalsIgnoreCase("bed");
            if (!isBed && (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)) {
                openDeleteConfirm(player, name);
            } else if (!isBed && event.getClick() == ClickType.RIGHT) {
                // Right-click opens an action menu (rename / move / delete).
                openHomeActions(player, name);
            } else {
                player.closeInventory();
                if (deniedByCombat(player)) return;
                // Just run AT's home command. If AT accepts it (cooldown/permission
                // OK), it fires ATTeleportEvent and our listener starts the timer.
                player.performCommand("advancedteleport:home " + name);
            }
            return;
        }
        if (action.equals("home_set")) {
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp == null) { player.closeInventory(); return; }
            if (!atp.canSetMoreHomes()) {
                player.sendMessage("\u00a7c" + "You can't set any more homes.");
                player.closeInventory();
                return;
            }
            // Ask the player to type a name in chat. We capture the next message.
            awaitingHomeName.put(player.getUniqueId(), "");
            player.closeInventory();
            player.sendMessage("\u00a7e" + "Type a name for your new home in chat.");
            player.sendMessage("\u00a77" + "Type \u00a7fcancel\u00a77 to abort.");
            scheduleNameInputTimeout(player.getUniqueId());
            return;
        }
        if (action.equals("home_back")) {
            openHomesGui(player);
            return;
        }
        if (action.startsWith("home_rename:")) {
            String name = action.substring("home_rename:".length());
            awaitingHomeName.put(player.getUniqueId(), name);
            player.closeInventory();
            player.sendMessage("\u00a7e" + "Type the new name for \"" + name + "\" in chat.");
            player.sendMessage("\u00a77" + "Type \u00a7fcancel\u00a77 to abort.");
            scheduleNameInputTimeout(player.getUniqueId());
            return;
        }
        if (action.startsWith("home_move:")) {
            String name = action.substring("home_move:".length());
            player.closeInventory();
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp != null && atp.getHome(name) != null) {
                atp.moveHome(name, player.getLocation(), player);
                player.sendMessage("\u00a7a" + "Home \"" + name + "\" moved to your location.");
            } else {
                player.sendMessage("\u00a7c" + "That home no longer exists.");
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) openHomesGui(player);
            }, 5L);
            return;
        }
        if (action.startsWith("home_tpfromactions:")) {
            String name = action.substring("home_tpfromactions:".length());
            player.closeInventory();
            if (deniedByCombat(player)) return;
            player.performCommand("advancedteleport:home " + name);
            return;
        }
        if (action.startsWith("home_delete_confirm:")) {
            String name = action.substring("home_delete_confirm:".length());
            ATPlayer atp = ATPlayer.getPlayer(player);
            if (atp != null) {
                atp.removeHome(name, player);
                player.sendMessage("\u00a7c" + "Home \"" + name + "\" deleted.");
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && hasOurGuiOpen(player)) openHomesGui(player);
            }, 10L);
            return;
        }
        if (action.equals("noop")) {
            return;
        }

        if (action.equals("rtplock_toggle")) {
            if (!player.isOp() && !player.hasPermission("atconfirmgui.rtplock")) {
                player.closeInventory();
                return;
            }
            rtpLocked = !rtpLocked;
            getConfig().set("rtp-locked", rtpLocked);
            saveConfig();
            player.sendMessage(rtpLocked
                    ? "\u00a7c" + "RTP is now LOCKED for all players."
                    : "\u00a7a" + "RTP is now ENABLED for all players.");
            openRtpLockGui(player); // refresh the pane
            return;
        }

        if (action.equals("backlock_toggle")) {
            if (!player.isOp() && !player.hasPermission("atconfirmgui.backlock")) {
                player.closeInventory();
                return;
            }
            backLocked = !backLocked;
            getConfig().set("back-locked", backLocked);
            saveConfig();
            player.sendMessage(backLocked
                    ? "\u00a7c" + "Back is now LOCKED for all players."
                    : "\u00a7a" + "Back is now ENABLED for all players.");
            openBackLockGui(player); // refresh the pane
            return;
        }

        if (action.equals("warplock_toggle")) {
            if (!player.isOp() && !player.hasPermission("atconfirmgui.warplock")) {
                player.closeInventory();
                return;
            }
            warpLocked = !warpLocked;
            getConfig().set("warp-locked", warpLocked);
            saveConfig();
            player.sendMessage(warpLocked
                    ? "\u00a7c" + "Warps are now LOCKED for all players."
                    : "\u00a7a" + "Warps are now ENABLED for all players.");
            openWarpLockGui(player); // refresh the pane
            return;
        }

        if (action.startsWith("warp_tp:")) {
            String name = action.substring("warp_tp:".length());
            player.closeInventory();
            if (warpBlockedFor(player)) return;
            if (deniedByCombat(player)) return;
            player.performCommand("advancedteleport:warp " + name);
            return;
        }

        // ---- Main menu buttons ----
        switch (action) {
            case "menu_homes" -> { openHomesGui(player); return; }
            case "menu_settings" -> { openSettings(player); return; }
            case "menu_rtp" -> { if (!rtpBlockedFor(player)) openRtpConfirm(player); return; }
            case "menu_rtp_locked" -> { rtpBlockedFor(player); return; }
            case "menu_back_locked" -> { backBlockedFor(player); return; }
            case "menu_warps" -> { openWarpsGui(player); return; }
            case "menu_spawn" -> {
                player.closeInventory();
                if (deniedByCombat(player)) return;
                player.performCommand("advancedteleport:spawn");
                return;
            }
            case "menu_back" -> {
                player.closeInventory();
                if (backBlockedFor(player)) return;
                if (deniedByCombat(player)) return;
                player.performCommand("advancedteleport:back");
                return;
            }
            case "menu_tpauto" -> {
                UUID pid = player.getUniqueId();
                if (autoAccept.contains(pid)) {
                    autoAccept.remove(pid);
                    stopAutoBar(player);
                    sendActionBar(player, Component.text("You disabled tpauto", NamedTextColor.RED), 40L);
                } else {
                    autoAccept.add(pid);
                    sendActionBarPermanent(player, Component.text("\u2714 tpauto enabled", NamedTextColor.GREEN));
                }
                savePlayerSettings();
                openMainMenu(player); // refresh the toggle button
                return;
            }
            default -> { /* fall through */ }
        }

        // ---- Settings toggles ----
        switch (action) {
            case "toggle_confirm" -> {
                if (noConfirmGui.contains(id)) noConfirmGui.remove(id);
                else noConfirmGui.add(id);
                savePlayerSettings();
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
                savePlayerSettings();
                refreshSettings(player);
                return;
            }
            case "toggle_tpa" -> {
                if (blockTpa.contains(id)) blockTpa.remove(id);
                else blockTpa.add(id);
                savePlayerSettings();
                refreshSettings(player);
                return;
            }
            case "toggle_tpahere" -> {
                if (blockTpahere.contains(id)) blockTpahere.remove(id);
                else blockTpahere.add(id);
                savePlayerSettings();
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
                // No-op now: the countdown is started by ATTeleportEvent (type TPR)
                // when AT actually begins the random teleport. We only guard combat
                // and the server-wide RTP lock (in case it was locked while the
                // confirm GUI was already open).
                if (deniedByCombat(player)) { player.closeInventory(); return; }
                if (rtpBlockedFor(player)) { player.closeInventory(); return; }
            } else {
                player.performCommand(cmd);
            }
        }
    }
}
