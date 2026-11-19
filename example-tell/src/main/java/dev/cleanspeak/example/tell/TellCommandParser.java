package dev.cleanspeak.example.tell;

import java.util.Locale;
import java.util.Optional;

public final class TellCommandParser {

    public enum Kind {
        TELL,
        REPLY
    }

    public record TellRequest(String targetName, String message) {
    }

    private TellCommandParser() {
    }

    public static Optional<Kind> kind(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "tell", "msg", "w", "etell", "emsg" -> Optional.of(Kind.TELL);
            case "reply", "r", "ereply" -> Optional.of(Kind.REPLY);
            default -> Optional.empty();
        };
    }

    public static TellRequest parseTell(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                message.append(' ');
            }
            message.append(args[i]);
        }
        return new TellRequest(args[0], message.toString());
    }

    public static String parseReply(String[] args) {
        if (args == null || args.length < 1) {
            return null;
        }
        return String.join(" ", args);
    }
}
