package com.example.coordcooldown;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class CoordCooldownPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<UUID> leakedPlayers = new HashSet<>();
    private final Map<UUID, Long> enderUse = new HashMap<>();
    private final Map<UUID, Long> windUse = new HashMap<>();
    private final Map<UUID, Long> maceUse = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("leakcord")).setExecutor(this);
        Objects.requireNonNull(getCommand("stopcords")).setExecutor(this);
        Objects.requireNonNull(getCommand("mace")).setExecutor(this);
        Objects.requireNonNull(getCommand("enderpearl")).setExecutor(this);
        Objects.requireNonNull(getCommand("windcharge")).setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::sendActionBarUpdates, 0L, 20L);
    }

    private void sendActionBarUpdates() {
        if (leakedPlayers.isEmpty()) return;

        List<String> displays = new ArrayList<>();
        for (UUID uuid : leakedPlayers) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && target.isOnline()) {
                int x = target.getLocation().getBlockX();
                int y = target.getLocation().getBlockY();
                int z = target.getLocation().getBlockZ();
                displays.add(ChatColor.GREEN + target.getName() + ": " + ChatColor.YELLOW + x + " " + y + " " + z);
            }
        }

        if (displays.isEmpty()) return;

        String actionbarMsg = String.join(ChatColor.GRAY + " | ", displays);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("coords.track")) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionbarMsg));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("leakcord")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /leakcord <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            leakedPlayers.add(target.getUniqueId());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aNow leaking coordinates of &e" + target.getName() + "&a."));
            return true;
        }

        if (cmd.equals("stopcords")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /stopcords <player>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            leakedPlayers.remove(target.getUniqueId());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cStopped leaking coordinates of &e" + target.getName() + "&c."));
            return true;
        }

        if (cmd.equals("mace") || cmd.equals("enderpearl") || cmd.equals("windcharge")) {
            if (args.length < 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + cmd + " <seconds>");
                return true;
            }
            try {
                double seconds = Double.parseDouble(args[0]);
                getConfig().set("cooldowns." + cmd, seconds);
                saveConfig();
                String name = cmd.substring(0, 1).toUpperCase() + cmd.substring(1);
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a" + name + " cooldown set to &e" + seconds + " &aseconds."));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Please specify a valid number.");
            }
            return true;
        }

        return false;
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;

        Entity projectile = event.getEntity();
        double cooldownSec = 0;
        String type = "";
        Material mat = null;
        Map<UUID, Long> cooldownMap = null;

        if (projectile instanceof EnderPearl) {
            cooldownSec = getConfig().getDouble("cooldowns.enderpearl", 0);
            type = "Ender Pearl";
            mat = Material.ENDER_PEARL;
            cooldownMap = enderUse;
        } else if (projectile instanceof WindCharge) {
            cooldownSec = getConfig().getDouble("cooldowns.windcharge", 0);
            type = "Wind Charge";
            mat = Material.WIND_CHARGE;
            cooldownMap = windUse;
        }

        if (mat != null && cooldownSec > 0) {
            long now = System.currentTimeMillis();
            long cdMillis = (long) (cooldownSec * 1000);
            UUID uuid = player.getUniqueId();

            if (cooldownMap.containsKey(uuid)) {
                long lastUse = cooldownMap.get(uuid);
                if (now - lastUse < cdMillis) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You must wait before using another " + type + ".");
                    return;
                }
            }

            cooldownMap.put(uuid, now);
            int ticks = (int) (cooldownSec * 20);
            player.setCooldown(mat, ticks);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        double maceCd = getConfig().getDouble("cooldowns.mace", 0);
        if (maceCd <= 0) return;

        boolean mainHandIsMace = attacker.getInventory().getItemInMainHand().getType() == Material.MACE;
        boolean offHandIsMace = attacker.getInventory().getItemInOffHand().getType() == Material.MACE;

        if (mainHandIsMace || offHandIsMace) {
            long now = System.currentTimeMillis();
            long cdMillis = (long) (maceCd * 1000);
            UUID uuid = attacker.getUniqueId();

            if (maceUse.containsKey(uuid)) {
                long lastUse = maceUse.get(uuid);
                if (now - lastUse < cdMillis) {
                    event.setCancelled(true);
                    attacker.sendMessage(ChatColor.RED + "Your Mace is on cooldown.");
                    return;
                }
            }

            maceUse.put(uuid, now);
            int ticks = (int) (maceCd * 20);
            attacker.setCooldown(Material.MACE, ticks);
        }
    }
}
