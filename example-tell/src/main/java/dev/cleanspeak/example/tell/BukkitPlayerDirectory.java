package dev.cleanspeak.example.tell;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public final class BukkitPlayerDirectory implements PlayerDirectory {
    @Override
    public Optional<TellPlayer> findOnline(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? Optional.empty() : Optional.of(new TellPlayer(player.getUniqueId(), player.getName()));
    }

    @Override
    public Optional<TellPlayer> findOnline(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player == null ? Optional.empty() : Optional.of(new TellPlayer(player.getUniqueId(), player.getName()));
    }

    @Override
    public boolean hasPermission(UUID playerId, String permission) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.hasPermission(permission);
    }
}
