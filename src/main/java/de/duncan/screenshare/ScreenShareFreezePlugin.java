package de.duncan.screenshare;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ScreenShareFreezePlugin extends JavaPlugin implements Listener {
    private static final Set<String> ALLOWED_COMMANDS_WHILE_FROZEN = Set.of("/msg", "/tell", "/w", "/r", "/reply", "/helpop");

    private final Map<UUID, FrozenPlayer> frozenPlayers = new HashMap<>();
    private boolean pluginTeleport;
    private BukkitTask freezeTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        freezeTask = Bukkit.getScheduler().runTaskTimer(this, this::tickFrozenPlayers, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (freezeTask != null) {
            freezeTask.cancel();
            freezeTask = null;
        }

        for (Map.Entry<UUID, FrozenPlayer> entry : frozenPlayers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                restoreFreezeProtection(player, entry.getValue());
            }
        }
        frozenPlayers.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ss")) {
            return handleScreenshare(sender, args);
        }

        if (command.getName().equalsIgnoreCase("dss")) {
            return handleDoneScreenshare(sender, args);
        }

        return false;
    }

    private boolean handleScreenshare(CommandSender sender, String[] args) {
        if (!sender.hasPermission("screenshare.use")) {
            send(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            send(sender, "reload-success");
            return true;
        }

        if (!(sender instanceof Player staff)) {
            send(sender, "only-players");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(color("&cBenutzung: /ss <spieler> oder /ss reload"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, "player-not-found");
            return true;
        }

        if (target.hasPermission("screenshare.bypass")) {
            send(sender, "cannot-freeze-bypass");
            return true;
        }

        if (frozenPlayers.containsKey(target.getUniqueId())) {
            send(sender, "already-frozen");
            return true;
        }

        if (isFixedLocationEnabled() && getFixedScreenshareLocation() == null) {
            send(sender, "world-not-found");
            return true;
        }

        TeleportPlan teleportPlan = createTeleportPlan(staff, target);
        if (teleportPlan == null || !teleportForScreenshare(staff, target, teleportPlan)) {
            send(sender, "teleport-failed");
            return true;
        }

        FrozenPlayer frozen = new FrozenPlayer(
                staff.getUniqueId(),
                teleportPlan.targetLocation().clone(),
                target.getAllowFlight(),
                target.isFlying()
        );
        frozenPlayers.put(target.getUniqueId(), frozen);
        applyFreezeProtection(target);
        enforceTeleportNextTick(staff, target, teleportPlan);

        send(sender, "frozen-staff", target);
        send(target, "frozen-target");
        return true;
    }

    private boolean handleDoneScreenshare(CommandSender sender, String[] args) {
        if (!sender.hasPermission("screenshare.use")) {
            send(sender, "no-permission");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(color("&cBenutzung: /dss <spieler>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(sender, "player-not-found");
            return true;
        }

        FrozenPlayer removed = frozenPlayers.remove(target.getUniqueId());
        if (removed == null) {
            send(sender, "not-frozen");
            return true;
        }

        restoreFreezeProtection(target, removed);
        send(sender, "unfrozen-staff", target);
        send(target, "unfrozen-target");
        return true;
    }

    private TeleportPlan createTeleportPlan(Player staff, Player target) {
        String mode = getConfig().getString("teleport-mode", "STAFF_TO_TARGET").toUpperCase(Locale.ROOT);
        Location fixedLocation = getFixedScreenshareLocation();
        if (fixedLocation != null) {
            Location staffLocation = fixedLocation.clone();
            Location targetLocation = staffLocation.clone().add(1.0, 0.0, 0.0);
            return new TeleportPlan(staffLocation, targetLocation);
        }

        if (mode.equals("TARGET_TO_STAFF")) {
            return new TeleportPlan(staff.getLocation().clone(), staff.getLocation().clone());
        }

        return new TeleportPlan(target.getLocation().clone(), target.getLocation().clone());
    }

    private boolean teleportForScreenshare(Player staff, Player target, TeleportPlan plan) {
        String mode = getConfig().getString("teleport-mode", "STAFF_TO_TARGET").toUpperCase(Locale.ROOT);
        pluginTeleport = true;
        try {
            if (getFixedScreenshareLocation() != null) {
                return staff.teleport(plan.staffLocation()) && target.teleport(plan.targetLocation());
            } else if (mode.equals("TARGET_TO_STAFF")) {
                return target.teleport(plan.targetLocation());
            } else {
                return staff.teleport(plan.staffLocation());
            }
        } finally {
            pluginTeleport = false;
        }
    }

    private void enforceTeleportNextTick(Player staff, Player target, TeleportPlan plan) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            pluginTeleport = true;
            try {
                if (staff.isOnline()) {
                    staff.teleport(plan.staffLocation());
                }
                if (target.isOnline() && isFrozen(target)) {
                    target.teleport(plan.targetLocation());
                }
            } finally {
                pluginTeleport = false;
            }
        }, 1L);
    }

    private boolean isFixedLocationEnabled() {
        return getConfig().getBoolean("screenshare-location.enabled", false)
                || getConfig().getString("teleport-mode", "").equalsIgnoreCase("FIXED_LOCATION");
    }

    private Location getFixedScreenshareLocation() {
        if (!isFixedLocationEnabled()) {
            return null;
        }

        String worldName = getConfig().getString("screenshare-location.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("Screenshare world not found in config: " + worldName);
            return null;
        }

        double x = getConfig().getDouble("screenshare-location.x", 0.5);
        double y = getConfig().getDouble("screenshare-location.y", 80.0);
        double z = getConfig().getDouble("screenshare-location.z", 0.5);
        float yaw = (float) getConfig().getDouble("screenshare-location.yaw", 0.0);
        float pitch = (float) getConfig().getDouble("screenshare-location.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pluginTeleport || !isFrozen(event.getPlayer())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        FrozenPlayer frozen = frozenPlayers.get(event.getPlayer().getUniqueId());
        if (frozen == null) {
            return;
        }

        boolean changedBlock = from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
        boolean changedHead = getConfig().getBoolean("freeze.lock-head", true)
                && (Float.compare(from.getYaw(), to.getYaw()) != 0 || Float.compare(from.getPitch(), to.getPitch()) != 0);

        if (changedBlock || changedHead) {
            Location locked = frozen.lockLocation().clone();
            event.setTo(locked);
            event.getPlayer().setVelocity(new Vector(0.0, 0.0, 0.0));
            event.getPlayer().setFallDistance(0.0F);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!getConfig().getBoolean("freeze.block-inventory", true)) {
            return;
        }

        if (event.getPlayer() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByFrozen(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandWhileFrozen(PlayerCommandPreprocessEvent event) {
        if (!isFrozen(event.getPlayer())) {
            return;
        }

        String command = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS_WHILE_FROZEN.contains(command)) {
            event.setCancelled(true);
            send(event.getPlayer(), "blocked-command");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (!getConfig().getBoolean("freeze.cancel-flying-kick", true) || !isFrozen(event.getPlayer())) {
            return;
        }

        String reason = ChatColor.stripColor(event.getReason()).toLowerCase(Locale.ROOT);
        if (reason.contains("flying") || reason.contains("flight") || reason.contains("moved wrongly")) {
            event.setCancelled(true);
            event.getPlayer().setFallDistance(0.0F);
            event.getPlayer().setVelocity(new Vector(0.0, 0.0, 0.0));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FrozenPlayer frozen = frozenPlayers.get(player.getUniqueId());
        if (frozen == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            FrozenPlayer stillFrozen = frozenPlayers.get(player.getUniqueId());
            if (stillFrozen == null || !player.isOnline()) {
                return;
            }

            Location rejoinLocation = getRejoinFreezeLocation(stillFrozen);
            pluginTeleport = true;
            try {
                applyFreezeProtection(player);
                player.teleport(rejoinLocation);
            } finally {
                pluginTeleport = false;
            }
            send(player, "frozen-target");
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isFrozen(event.getPlayer())) {
            getLogger().info(event.getPlayer().getName() + " left while frozen and will stay frozen on rejoin.");
        }
    }

    private boolean isFrozen(Player player) {
        return frozenPlayers.containsKey(player.getUniqueId());
    }

    private void tickFrozenPlayers() {
        for (UUID uuid : frozenPlayers.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            player.setVelocity(new Vector(0.0, 0.0, 0.0));
            player.setFallDistance(0.0F);
            applyFreezeProtection(player);

            if (getConfig().getBoolean("freeze.close-inventory-tick", true)) {
                player.closeInventory();
            }
        }
    }

    private void applyFreezeProtection(Player player) {
        if (!getConfig().getBoolean("freeze.prevent-flying-kick", true)) {
            return;
        }

        player.setAllowFlight(true);
        player.setFlying(false);
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0.0, 0.0, 0.0));
    }

    private void restoreFreezeProtection(Player player, FrozenPlayer frozen) {
        if (!getConfig().getBoolean("freeze.prevent-flying-kick", true)) {
            return;
        }

        player.setFlying(frozen.wasFlying());
        player.setAllowFlight(frozen.hadAllowFlight());
        player.setFallDistance(0.0F);
    }

    private Location getRejoinFreezeLocation(FrozenPlayer frozen) {
        Location fixedLocation = getFixedScreenshareLocation();
        if (fixedLocation != null) {
            return fixedLocation.add(1.0, 0.0, 0.0);
        }
        return frozen.lockLocation().clone();
    }

    private void send(CommandSender sender, String key) {
        sender.sendMessage(color(getMessage(key)));
    }

    private void send(CommandSender sender, String key, Player player) {
        sender.sendMessage(color(getMessage(key).replace("%player%", player.getName())));
    }

    private String getMessage(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String message = getConfig().getString("messages." + key, key);
        return prefix + message;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private record FrozenPlayer(UUID staffUuid, Location lockLocation, boolean hadAllowFlight, boolean wasFlying) {
    }

    private record TeleportPlan(Location staffLocation, Location targetLocation) {
    }
}
