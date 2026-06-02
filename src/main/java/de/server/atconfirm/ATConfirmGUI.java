package de.server.atconfirm;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * ATConfirmGUI - a small add-on for AdvancedTeleport that adds confirmation
 * GUIs for sending and accepting teleport requests.
 *
 * Flow:
 *  - /tpa <player>      -> opens a "Send request to <player>?" confirm GUI for the sender
 *  - /tpahere <player>  -> same, for tpahere
 *  - Incoming request   -> receiver gets AT's normal chat message with [Accept] button
 *  - Clicking [Accept] or typing /tpaccept -> opens "Accept request?" confirm GUI
 *  - Clicking green pane -> teleport is executed
 *
 * Confirming runs the real AdvancedTeleport command via the namespaced form
 * (advancedteleport:tpa etc.) so it always reaches AT regardless of aliasing.
 */
public final class ATConfirmGUI extends JavaPlugin implements Listener {

    // Key used to tag the buttons in our GUIs so we know what to run on click.
    private NamespacedKey actionKey;

    @Override
    public void onEnable() {
        // Hard dependency check so we fail loudly instead of silently.
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
                if (args.length < 1) {
                    player.sendMessage(ChatColor.RED + "Usage: /tpa <player>");
                    return true;
                }
                openSendConfirm(player, args[0], "tpa");
                return true;
            }
            case "tpahere" -> {
                if (args.length < 1) {
                    player.sendMessage(ChatColor.RED + "Usage: /tpahere <player>");
                    return true;
                }
                openSendConfirm(player, args[0], "tpahere");
                return true;
            }
            case "tpaccept" -> {
                // AdvancedTeleport only has ONE accept command. When typed
                // manually we don't know the type, so use a neutral label.
                openAcceptConfirmFor(player, ChatColor.DARK_GRAY + "Accept request?");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------------
    //  GUI builders
    // ---------------------------------------------------------------------

    private void openSendConfirm(Player player, String target, String baseCmd) {
        String pretty = baseCmd.equals("tpahere") ? "TPA-Here" : "TPA";
        Inventory inv = Bukkit.createInventory(player, 27,
                ChatColor.DARK_GRAY + "Send " + pretty + " to " + target + "?");

        // Confirm -> run the real AT command WITH the target name.
        inv.setItem(11, button(Material.LIME_STAINED_GLASS_PANE,
                ChatColor.GREEN + "Confirm",
                List.of(ChatColor.GRAY + "Send the request to " + target + "."),
                "[close];advancedteleport:" + baseCmd + " " + target));

        // Cancel -> just close.
        inv.setItem(15, button(Material.RED_STAINED_GLASS_PANE,
                ChatColor.RED + "Cancel",
                List.of(ChatColor.GRAY + "Do not send the request."),
                "[close]"));

        player.openInventory(inv);
    }

    private void openAcceptConfirmFor(Player player, String title) {
        Inventory inv = Bukkit.createInventory(player, 27, title);

        // AdvancedTeleport has a single accept command (tpaccept) that handles
        // both tpa and tpahere requests, so we always run that one.
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
        if (action == null) return; // not one of our buttons

        // It's our GUI: block item movement.
        event.setCancelled(true);

        // Actions are separated by ';' so "[close];advancedteleport:tpaccept" runs both.
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
