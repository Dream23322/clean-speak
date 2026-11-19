package dev.cleanspeak.example.tell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class BukkitMessageSink implements MessageSink {

    private final JavaPlugin plugin;

    public BukkitMessageSink(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void send(UUID playerId, String message) {
        Runnable task = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message, NamedTextColor.GRAY));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
