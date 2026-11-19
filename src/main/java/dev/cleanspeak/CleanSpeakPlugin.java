package dev.cleanspeak;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.chat.EssentialsPreSendAdapter;
import dev.cleanspeak.chat.PlayerJoinListener;
import dev.cleanspeak.chat.PublicChatListener;
import dev.cleanspeak.chat.RawCommandListener;
import dev.cleanspeak.staff.CleanSpeakCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class CleanSpeakPlugin extends JavaPlugin {

    private CleanSpeakRuntime runtime;

    @Override
    public void onEnable() {
        try {
            runtime = new CleanSpeakRuntime(this);
            runtime.start();
            getServer().getPluginManager().registerEvents(new PublicChatListener(this, runtime), this);
            getServer().getPluginManager().registerEvents(new PlayerJoinListener(runtime), this);
            getServer().getPluginManager().registerEvents(new RawCommandListener(runtime), this);

            PluginCommand command = getCommand("cleanspeak");
            if (command == null) {
                throw new IllegalStateException("cleanspeak command is missing from plugin.yml");
            }
            CleanSpeakCommand executor = new CleanSpeakCommand(this, runtime);
            command.setExecutor(executor);
            command.setTabCompleter(executor);

            runtime.api().registerAdapter(new AdapterRegistration(
                    "paper-public-chat",
                    getPluginMeta().getVersion(),
                    "Paper AsyncChatEvent",
                    true,
                    "RULES_ONLY default preserves signed chat and chat formatters; ASYNC_REPLAY is experimental and unsigned."
            ));

            if (getServer().getPluginManager().getPlugin("Essentials") != null) {
                try {
                    getServer().getPluginManager().registerEvents(new EssentialsPreSendAdapter(runtime), this);
                    getLogger().info("EssentialsX private-message adapter registered (rules-only synchronous path).");
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING,
                            "EssentialsX is installed but its private-message adapter could not be registered; /tell may be unprotected.", t);
                }
            }

            getLogger().info("CleanSpeak enabled. Shadow mode is " + (runtime.config().text.shadowMode ? "ON" : "OFF")
                    + "; no automatic bans exist in this plugin.");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "CleanSpeak failed to enable; disabling plugin to avoid a partially active filter.", t);
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Throwable ignored) {
                }
                runtime = null;
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    public CleanSpeakRuntime runtime() {
        return runtime;
    }
}
