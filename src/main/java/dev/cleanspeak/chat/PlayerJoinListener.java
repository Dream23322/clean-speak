package dev.cleanspeak.chat;

import dev.cleanspeak.CleanSpeakRuntime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {
    private final CleanSpeakRuntime runtime;

    public PlayerJoinListener(CleanSpeakRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        runtime.playerNotices().joinNotice(event.getPlayer());
    }
}
