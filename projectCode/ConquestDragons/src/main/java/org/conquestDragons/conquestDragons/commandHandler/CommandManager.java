package org.conquestDragons.conquestDragons.commandHandler;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.conquestDragons.conquestDragons.commandHandler.subcommandHandler.AdminCommandManager;
import org.conquestDragons.conquestDragons.commandHandler.subcommandHandler.UserCommandManager;
import org.conquestDragons.conquestDragons.cooldownHandler.CommandCooldownManager;
import org.conquestDragons.conquestDragons.responseHandler.MessageResponseManager;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.AdminMessageModels;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.GenericMessageModels;
import org.conquestDragons.conquestDragons.responseHandler.messageModels.UserMessageModels;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🧭 CommandManager (ConquestDragons)
 * Central executor and tab completer for /dragons and its aliases.
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private static final Map<String, String> ALIAS_MAP = new HashMap<>();

    static {
        registerAliases();
    }

    private static void registerAliases() {
        // Core user commands
        ALIAS_MAP.put("help", "help");
        ALIAS_MAP.put("h", "help");

        // (Add more user-facing roots as you implement them)
        // ALIAS_MAP.put("create", "create");
        // ALIAS_MAP.put("invite", "invite");
        // ALIAS_MAP.put("join", "join");

        // Admin group root
        ALIAS_MAP.put("admin", "admin");

        // Utility (mirrors your Compressor layout; can be handled in user/admin as you prefer)
        ALIAS_MAP.put("reload", "reload");
        ALIAS_MAP.put("r", "reload");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        // Mirror Compressor: player-only entry
        if (!(sender instanceof Player player)) {
            return sendNotPlayer(sender);
        }

        // Global per-player cooldown (mirror Compressor)
        if (CommandCooldownManager.isOnCooldown(player.getUniqueId())) {
            MessageResponseManager.send(player, GenericMessageModels.COMMAND_ON_COOLDOWN);
            return true;
        }
        CommandCooldownManager.mark(player.getUniqueId());

        if (args.length == 0) {
            return UserCommandManager.sendUsageHint(player);
        }

        final String input = args[0].toLowerCase();
        final String subcommand = ALIAS_MAP.getOrDefault(input, null);

        if (subcommand == null) {
            MessageResponseManager.send(player, UserMessageModels.USER_HELP_USAGE);
            return true;
        }

        if (subcommand.equals("admin")) {
            return handleAdmin(player, args);
        }

        // Plug straight into user handler (switch-based)
        return UserCommandManager.handle(player, subcommand, args);
    }

    private boolean handleAdmin(Player player, String[] args) {
        if (args.length < 2) {
            MessageResponseManager.send(player, AdminMessageModels.ADMIN_HELP_USAGE);
            return true;
        }
        return AdminCommandManager.handle(player, args);
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        return AutoTabManager.getSuggestions(sender, args);
    }

    public static boolean sendNotPlayer(CommandSender sender) {
        MessageResponseManager.send(sender, GenericMessageModels.NOT_PLAYER);
        return true;
    }
}
