# ATConfirmGUI

A small add-on for **AdvancedTeleport** (Paper 1.21.x) that adds confirmation
GUIs:

- `/tpa <player>` → opens a **"Send request to \<player\>?"** GUI for the sender
- `/tpahere <player>` → same, for tpahere
- When a request arrives, the receiver gets AdvancedTeleport's normal chat
  message **and** an **"Accept request from \<player\>?"** GUI pops up
- `/tpaccept` also opens the accept GUI (one command handles both types)

Green pane = confirm/accept, red pane = cancel/deny.

---

## Part A — Build the .jar with GitHub Actions (no software needed)

1. Create a free account at https://github.com if you don't have one.
2. Click **New repository**, give it any name, set it to **Private**, create it.
3. On the repo page click **Add file → Upload files**.
4. Drag in **everything** from this folder, keeping the folder structure:
   ```
   pom.xml
   libs/AdvancedTeleport.jar
   src/main/java/de/server/atconfirm/ATConfirmGUI.java
   src/main/resources/plugin.yml
   .github/workflows/build.yml
   ```
   (The easiest way: zip is NOT needed — GitHub's uploader keeps folders if you
   drag the folders themselves. If it flattens them, upload folder by folder.)
5. Click **Commit changes**. The build starts automatically.
6. Go to the **Actions** tab, click the latest run, wait for the green check.
7. Scroll to **Artifacts** at the bottom → download **ATConfirmGUI**.
8. Inside the zip is **ATConfirmGUI.jar** — that's your plugin.

---

## Part B — Install on your server

1. Put **ATConfirmGUI.jar** in your server's `plugins/` folder
   (next to AdvancedTeleport).
2. **Hand the commands over to this plugin.** Open AdvancedTeleport's
   `config.yml` and set:
   ```yaml
   disabled-commands:
   - tpa
   - tpahere
   - tpaccept
   ```
   This stops AdvancedTeleport from grabbing those three commands, so our GUI
   versions take over. AdvancedTeleport still does the actual teleporting
   behind the scenes via its internal `advancedteleport:` commands.
3. **Fully restart** the server (not just `/reload`). Command changes only
   apply on a real restart.

---

## Part C — CombatLog compatibility

Your CombatLog plugin blocks commands by name via its `blocked-commands` list.
Because our `/tpa`, `/tpahere`, `/rtp` have the same names, they get blocked in
combat automatically (the GUI never opens). To also close the loophole where a
GUI opened *before* combat is clicked *during* combat, add the namespaced AT
commands to `plugins/CombatLog/config.yml`:

```yaml
blocked-commands:
  - tpa
  - tpahere
  - rtp
  - tpaccept
  - tpdeny
  - advancedteleport:tpa
  - advancedteleport:tpahere
  - advancedteleport:tpaccept
  - advancedteleport:tpdeny
  - advancedteleport:tpr
```

Test it: enter combat, open a GUI you opened earlier, click Accept — it should
be blocked. If the namespaced form isn't blocked, your CombatLog may only match
the first word; in that case the plain entries above still cover normal use.

---

## Note on cooldowns & warm-ups

This plugin does NOT teleport by itself. It only opens a GUI and then runs
AdvancedTeleport's real command. So all your AT settings — the 5-second
warm-up, cooldowns, costs — keep working exactly as before.

---

## How it works

When you confirm in a GUI, the plugin runs AdvancedTeleport's real command
through its namespaced form (e.g. `advancedteleport:tpaccept`), which always
reaches AdvancedTeleport even though the plain command now opens our GUI.

## If something doesn't work

- **GUI doesn't open, normal AT message instead** → the `disabled-commands`
  list wasn't applied or the server wasn't fully restarted.
- **Clicking confirm does nothing** → check the server console for an error and
  send it over. The internal command name may differ on your AT version.
- **Build fails on the Paper version** → change `1.21.4-R0.1-SNAPSHOT` in
  `pom.xml` to the closest available Paper API version, then re-upload pom.xml.
