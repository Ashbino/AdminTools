package com.admintools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AdminTools extends JavaPlugin implements Listener, CommandExecutor {

    private static final String[] COMMANDS = {
            "gmc", "gms", "gma", "gmsp", "vanish", "freeze", "fly", "speed",
            "heal", "feed", "god", "invsee", "tphere", "sc", "admintools"
    };

    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> god = new HashSet<>();

    private File dataFile;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);
        for (String c : COMMANDS) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this);
        }

        // Re-apply vanish to anyone online (e.g. after /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished.contains(p.getUniqueId())) applyVanish(p);
        }

        // Action bar reminders
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            boolean vBar = getConfig().getBoolean("vanish.actionbar-reminder", true);
            boolean fBar = getConfig().getBoolean("freeze.actionbar-reminder", true);
            if (vBar) {
                Component c = color(getConfig().getString("vanish.actionbar-message", ""));
                for (UUID id : vanished) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(c);
                }
            }
            if (fBar) {
                Component c = color(getConfig().getString("freeze.actionbar-message", ""));
                for (UUID id : frozen) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(c);
                }
            }
        }, 20L, 40L);

        getLogger().info("AdminTools enabled.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(vanished)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) removeVanishEffects(p);
        }
        saveData();
    }

    // ----------------------------------------------------------------- data

    private void loadData() {
        vanished.clear();
        frozen.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : data.getStringList("frozen")) {
            try { frozen.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        if (getConfig().getBoolean("vanish.persist-on-rejoin", true)) {
            for (String s : data.getStringList("vanished")) {
                try { vanished.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void saveData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("vanished", vanished.stream().map(UUID::toString).toList());
        data.set("frozen", frozen.stream().map(UUID::toString).toList());
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- messages

    private Component color(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }

    private String raw(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private String fill(String m, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) m = m.replace(pairs[i], pairs[i + 1]);
        return m;
    }

    private void send(CommandSender to, String key, String... pairs) {
        String prefix = getConfig().getString("messages.prefix", "");
        to.sendMessage(color(prefix + fill(raw(key), pairs)));
    }

    private String state(boolean on) {
        return raw(on ? "state-on" : "state-off");
    }

    // ------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "gmc" -> gamemode(sender, args, GameMode.CREATIVE);
            case "gms" -> gamemode(sender, args, GameMode.SURVIVAL);
            case "gma" -> gamemode(sender, args, GameMode.ADVENTURE);
            case "gmsp" -> gamemode(sender, args, GameMode.SPECTATOR);
            case "vanish" -> vanishCmd(sender);
            case "freeze" -> freezeCmd(sender, args);
            case "fly" -> flyCmd(sender, args);
            case "speed" -> speedCmd(sender, args);
            case "heal" -> healCmd(sender, args);
            case "feed" -> feedCmd(sender, args);
            case "god" -> godCmd(sender, args);
            case "invsee" -> invseeCmd(sender, args);
            case "tphere" -> tphereCmd(sender, args);
            case "sc" -> staffChatCmd(sender, args);
            case "admintools" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    send(sender, "reload");
                } else {
                    sender.sendMessage(color("&b/admintools reload"));
                }
            }
            default -> { }
        }
        return true;
    }

    /** Resolve optional [player] argument at args[idx]; returns null (and messages) on failure. */
    private Player target(CommandSender sender, String[] args, int idx, String othersPerm) {
        if (args.length > idx) {
            boolean self = sender instanceof Player p && p.getName().equalsIgnoreCase(args[idx]);
            if (!self && !sender.hasPermission(othersPerm)) {
                send(sender, "no-others");
                return null;
            }
            Player t = Bukkit.getPlayerExact(args[idx]);
            if (t == null) {
                send(sender, "player-not-found", "%player%", args[idx]);
                return null;
            }
            return t;
        }
        if (sender instanceof Player p) return p;
        send(sender, "only-players");
        return null;
    }

    private void gamemode(CommandSender sender, String[] args, GameMode mode) {
        Player t = target(sender, args, 0, "admintools.gamemode.others");
        if (t == null) return;
        t.setGameMode(mode);
        String name = mode.name().toLowerCase();
        send(t, "gamemode-self", "%mode%", name);
        if (t != sender) send(sender, "gamemode-other", "%player%", t.getName(), "%mode%", name);
    }

    // --- vanish

    private void vanishCmd(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            send(sender, "only-players");
            return;
        }
        boolean on = !vanished.contains(p.getUniqueId());
        boolean fake = getConfig().getBoolean("vanish.fake-messages", false);
        if (on) {
            vanished.add(p.getUniqueId());
            applyVanish(p);
            if (fake) Bukkit.broadcast(color(fill(getConfig().getString("vanish.fake-leave-message", ""),
                    "%player%", p.getName())));
        } else {
            vanished.remove(p.getUniqueId());
            removeVanishEffects(p);
            if (fake) Bukkit.broadcast(color(fill(getConfig().getString("vanish.fake-join-message", ""),
                    "%player%", p.getName())));
        }
        saveData();
        send(p, on ? "vanish-on" : "vanish-off");
    }

    private void applyVanish(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            if (!other.hasPermission("admintools.vanish.see")) {
                other.hidePlayer(this, p); // hides model and removes from tab list
            }
        }
        if (getConfig().getBoolean("vanish.potion-invisibility", true)) {
            // ambient=false, particles=false, icon=false -> no swirls, no HUD icon
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        if (getConfig().getBoolean("vanish.disable-collision", true)) p.setCollidable(false);
        p.setSleepingIgnored(true);
    }

    private void removeVanishEffects(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(this, p);
        }
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setCollidable(true);
        p.setSleepingIgnored(false);
    }

    // --- freeze

    private void freezeCmd(CommandSender sender, String[] args) {
        if (args.length < 1) {
            send(sender, "freeze-usage");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            send(sender, "player-not-found", "%player%", args[0]);
            return;
        }
        UUID id = t.getUniqueId();
        if (frozen.remove(id)) {
            saveData();
            send(t, "freeze-off-target");
            send(sender, "freeze-off-sender", "%player%", t.getName());
        } else {
            if (t.hasPermission("admintools.freeze.bypass")) {
                send(sender, "freeze-bypass");
                return;
            }
            frozen.add(id);
            saveData();
            send(t, "freeze-on-target");
            send(sender, "freeze-on-sender", "%player%", t.getName());
        }
    }

    // --- fly / speed / heal / feed / god

    private void flyCmd(CommandSender sender, String[] args) {
        Player t = target(sender, args, 0, "admintools.fly.others");
        if (t == null) return;
        boolean on = !t.getAllowFlight();
        t.setAllowFlight(on);
        if (on) t.setFlying(true); else t.setFlying(false);
        send(t, "fly-self", "%state%", state(on));
        if (t != sender) send(sender, "fly-other", "%player%", t.getName(), "%state%", state(on));
    }

    private void speedCmd(CommandSender sender, String[] args) {
        if (args.length < 1) {
            send(sender, "speed-usage");
            return;
        }
        double max = getConfig().getDouble("speed.max", 10);
        double value;
        try {
            value = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            send(sender, "speed-invalid", "%max%", String.valueOf((int) max));
            return;
        }
        if (value < 0 || value > max) {
            send(sender, "speed-invalid", "%max%", String.valueOf((int) max));
            return;
        }
        Player t = target(sender, args, 1, "admintools.speed.others");
        if (t == null) return;
        float f = (float) (Math.min(value, 10) / 10.0);
        if (t.isFlying()) t.setFlySpeed(f); else t.setWalkSpeed(f);
        String shown = args[0];
        send(t, "speed-self", "%speed%", shown);
        if (t != sender) send(sender, "speed-other", "%player%", t.getName(), "%speed%", shown);
    }

    private void healCmd(CommandSender sender, String[] args) {
        Player t = target(sender, args, 0, "admintools.heal.others");
        if (t == null) return;
        AttributeInstance max = t.getAttribute(Attribute.MAX_HEALTH);
        t.setHealth(max != null ? max.getValue() : 20.0);
        t.setFoodLevel(20);
        t.setSaturation(20f);
        t.setFireTicks(0);
        send(t, "heal-self");
        if (t != sender) send(sender, "heal-other", "%player%", t.getName());
    }

    private void feedCmd(CommandSender sender, String[] args) {
        Player t = target(sender, args, 0, "admintools.feed.others");
        if (t == null) return;
        t.setFoodLevel(20);
        t.setSaturation(20f);
        send(t, "feed-self");
        if (t != sender) send(sender, "feed-other", "%player%", t.getName());
    }

    private void godCmd(CommandSender sender, String[] args) {
        Player t = target(sender, args, 0, "admintools.god.others");
        if (t == null) return;
        boolean on;
        if (god.remove(t.getUniqueId())) {
            on = false;
        } else {
            god.add(t.getUniqueId());
            on = true;
        }
        send(t, "god-self", "%state%", state(on));
        if (t != sender) send(sender, "god-other", "%player%", t.getName(), "%state%", state(on));
    }

    // --- invsee / tphere / staff chat

    private void invseeCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            send(sender, "only-players");
            return;
        }
        if (args.length < 1) {
            send(sender, "invsee-usage");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            send(sender, "player-not-found", "%player%", args[0]);
            return;
        }
        if (t.equals(p)) {
            send(sender, "invsee-self");
            return;
        }
        p.openInventory(t.getInventory());
    }

    private void tphereCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            send(sender, "only-players");
            return;
        }
        if (args.length < 1) {
            send(sender, "tphere-usage");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            send(sender, "player-not-found", "%player%", args[0]);
            return;
        }
        t.teleport(p.getLocation());
        send(sender, "tphere-done", "%player%", t.getName());
    }

    private void staffChatCmd(CommandSender sender, String[] args) {
        if (args.length < 1) {
            send(sender, "sc-usage");
            return;
        }
        String message = String.join(" ", args);
        String name = sender instanceof Player p ? p.getName() : "Console";
        Component line = color(fill(raw("sc-format"), "%player%", name, "%message%", message));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("admintools.staffchat")) online.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    // --------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joiner = e.getPlayer();

        if (vanished.contains(joiner.getUniqueId())) {
            applyVanish(joiner);
            if (getConfig().getBoolean("vanish.silent-join-quit", true)) e.joinMessage(null);
            send(joiner, "vanish-on");
        }
        if (frozen.contains(joiner.getUniqueId())) {
            send(joiner, "freeze-on-target");
        }
        if (!joiner.hasPermission("admintools.vanish.see")) {
            for (UUID id : vanished) {
                Player v = Bukkit.getPlayer(id);
                if (v != null && !v.equals(joiner)) joiner.hidePlayer(this, v);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        if (vanished.contains(id)) {
            if (getConfig().getBoolean("vanish.silent-join-quit", true)) e.quitMessage(null);
            removeVanishEffects(p); // don't leave an infinite potion saved on the player
            if (!getConfig().getBoolean("vanish.persist-on-rejoin", true)) {
                vanished.remove(id);
                saveData();
            }
        }
        god.remove(id);

        if (frozen.contains(id)) {
            if (getConfig().getBoolean("freeze.alert-staff-on-logout", true)) {
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("admintools.freeze")) {
                        send(staff, "freeze-logout-alert", "%player%", p.getName());
                    }
                }
            }
            String cmd = getConfig().getString("freeze.logout-command", "");
            if (cmd != null && !cmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }
    }

    // vanish protections

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (!getConfig().getBoolean("vanish.disable-mob-targeting", true)) return;
        if (e.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!getConfig().getBoolean("vanish.disable-item-pickup", true)) return;
        if (e.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // freeze protections

    private boolean isFrozen(Player p, String option) {
        return frozen.contains(p.getUniqueId()) && getConfig().getBoolean("freeze." + option, true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isFrozen(e.getPlayer(), "block-movement")) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());     // let them look around
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (isFrozen(e.getPlayer(), "block-break")) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer(), "block-place")) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer(), "block-interact")) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isFrozen(e.getPlayer(), "block-drop")) e.setCancelled(true);
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isFrozen(p, "block-commands")) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        List<String> allowed = getConfig().getStringList("freeze.allowed-commands");
        for (String a : allowed) {
            if (a.equalsIgnoreCase(label)) return;
        }
        e.setCancelled(true);
        send(p, "freeze-command-blocked");
    }

    // damage: god mode + frozen

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (god.contains(p.getUniqueId()) || isFrozen(p, "block-damage")) e.setCancelled(true);
    }

    // invsee: view-only unless permitted

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (shouldBlockInvEdit(e.getView().getTopInventory().getHolder(), e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (shouldBlockInvEdit(e.getView().getTopInventory().getHolder(), e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    private boolean shouldBlockInvEdit(Object holder, org.bukkit.entity.HumanEntity clicker) {
        return holder instanceof Player owner
                && !owner.getUniqueId().equals(clicker.getUniqueId())
                && !clicker.hasPermission("admintools.invsee.modify");
    }
}
