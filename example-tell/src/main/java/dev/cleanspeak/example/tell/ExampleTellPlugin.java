package dev.cleanspeak.example.tell;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.CleanSpeakApi;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Working example of a third-party /tell-style plugin using CleanSpeak's
 * pre-delivery API. It is intentionally a separate plugin so the messaging
 * plugin keeps ownership of permissions, ignore lists, reply targets and
 * delivery.
 */
public final class ExampleTellPlugin extends JavaPlugin {

    private TellService tellService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<CleanSpeakApi> provider =
                getServer().getServicesManager().getRegistration(CleanSpeakApi.class);
        if (provider == null || provider.getProvider() == null) {
            getLogger().severe("CleanSpeak API is unavailable; the example /tell plugin will not enable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CleanSpeakApi api = provider.getProvider();
        PlayerDirectory directory = new BukkitPlayerDirectory();
        IgnoreStore ignores = new YamlIgnoreStore(this);
        MessageSink messages = new BukkitMessageSink(this);
        MainThreadScheduler scheduler = new BukkitSchedulerAdapter(this);
        long timeout = getConfig().getLong("moderation-timeout-ms", 5000L);
        int maxPending = getConfig().getInt("conversation-max-pending", 8);
        int maxConversations = getConfig().getInt("conversation-max-active", 2000);
        tellService = new TellService(api, directory, ignores, messages, scheduler,
                maxPending, maxConversations, timeout);

        TellCommand tellCommand = new TellCommand(tellService);
        bind("tell", tellCommand);
        bind("reply", tellCommand);

        IgnoreCommand ignoreCommand = new IgnoreCommand(directory, ignores, messages);
        bind("ignore", ignoreCommand);
        bind("unignore", ignoreCommand);
        bind("ignorelist", ignoreCommand);

        api.registerAdapter(new AdapterRegistration(
                "example-tell",
                getPluginMeta().getVersion(),
                "/tell,/msg,/w,/reply,/r,/ignore,/unignore",
                true,
                "Async pre-delivery API example; messaging plugin owns delivery and exactly-once handling."
        ));
        getLogger().info("Example /tell plugin enabled via CleanSpeak API. Do not install it beside another /tell plugin with conflicting commands.");
    }

    private void bind(String name, Object executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command " + name + " is missing from plugin.yml");
            return;
        }
        command.setExecutor((org.bukkit.command.CommandExecutor) executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    @Override
    public void onDisable() {
        if (tellService != null) {
            tellService.close();
            tellService = null;
        }
    }
}
