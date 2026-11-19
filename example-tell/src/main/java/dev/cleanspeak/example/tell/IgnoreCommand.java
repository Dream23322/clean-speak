package dev.cleanspeak.example.tell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class IgnoreCommand implements CommandExecutor, TabCompleter {

    private final PlayerDirectory directory;
    private final IgnoreStore ignores;
    private final MessageSink messages;

    public IgnoreCommand(PlayerDirectory directory, IgnoreStore ignores, MessageSink messages) {
        this.directory = directory;
        this.ignores = ignores;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("ignorelist")) {
            List<UUID> ignored = ignores.ignored(player.getUniqueId());
            if (ignored.isEmpty()) {
                messages.send(player.getUniqueId(), "Your ignore list is empty.");
            } else {
                StringBuilder names = new StringBuilder();
                for (UUID id : ignored) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(directory.findOnline(id).map(TellPlayer::name).orElse(id.toString()));
                }
                messages.send(player.getUniqueId(), "Ignored: " + names);
            }
            return true;
        }
        if (args.length < 1) {
            messages.send(player.getUniqueId(), "Usage: /" + name + " <player>");
            return true;
        }
        Optional<TellPlayer> target = directory.findOnline(args[0]);
        if (target.isEmpty()) {
            messages.send(player.getUniqueId(), "That player is not online.");
            return true;
        }
        if (target.get().id().equals(player.getUniqueId())) {
            messages.send(player.getUniqueId(), "You cannot ignore yourself.");
            return true;
        }
        if (name.equals("unignore")) {
            ignores.unignore(player.getUniqueId(), target.get().id());
            messages.send(player.getUniqueId(), "You are no longer ignoring " + target.get().name() + ".");
        } else {
            ignores.ignore(player.getUniqueId(), target.get().id());
            messages.send(player.getUniqueId(), "You are now ignoring " + target.get().name() + ".");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && !command.getName().equalsIgnoreCase("ignorelist")) {
            return org.bukkit.Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
