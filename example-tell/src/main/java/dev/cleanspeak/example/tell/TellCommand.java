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

public final class TellCommand implements CommandExecutor, TabCompleter {

    private final TellService service;

    public TellCommand(TellService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        Optional<TellCommandParser.Kind> parsedKind = TellCommandParser.kind(label);
        if (parsedKind.isEmpty()) {
            parsedKind = TellCommandParser.kind(command.getName());
        }
        if (parsedKind.isEmpty()) {
            player.sendMessage(Component.text("Unknown private message command.", NamedTextColor.RED));
            return true;
        }
        TellPlayer tellPlayer = new TellPlayer(player.getUniqueId(), player.getName());
        if (parsedKind.get() == TellCommandParser.Kind.TELL) {
            TellCommandParser.TellRequest request = TellCommandParser.parseTell(args);
            if (request == null) {
                player.sendMessage(Component.text("Usage: /tell <player> <message>", NamedTextColor.RED));
                return true;
            }
            service.tell(tellPlayer, request.targetName(), request.message());
        } else {
            String message = TellCommandParser.parseReply(args);
            if (message == null || message.isBlank()) {
                player.sendMessage(Component.text("Usage: /reply <message>", NamedTextColor.RED));
                return true;
            }
            service.reply(tellPlayer, message);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && TellCommandParser.kind(alias).orElse(TellCommandParser.kind(command.getName()).orElse(null))
                == TellCommandParser.Kind.TELL) {
            return org.bukkit.Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
