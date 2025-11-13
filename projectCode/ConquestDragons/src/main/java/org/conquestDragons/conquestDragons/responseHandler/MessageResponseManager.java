package org.conquestDragons.conquestDragons.responseHandler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.conquestClans.conquestclans.ConquestClans;
import org.conquestClans.conquestclans.configurationHandler.configurationFiles.integrationFiles.PlaceholderAPIManager;
import org.conquestClans.conquestclans.configurationHandler.configurationFiles.messageFiles.AdminMessagesFile;
import org.conquestClans.conquestclans.configurationHandler.configurationFiles.messageFiles.GenericMessagesFile;
import org.conquestClans.conquestclans.configurationHandler.configurationFiles.messageFiles.UserMessagesFile;
import org.conquestClans.conquestclans.responseHandler.effectHandler.*;
import org.conquestClans.conquestclans.responseHandler.messageModels.AdminMessageModels;
import org.conquestClans.conquestclans.responseHandler.messageModels.GenericMessageModels;
import org.conquestClans.conquestclans.responseHandler.messageModels.UserMessageModels;

import java.util.*;
import java.util.logging.Logger;

/**
 * 📬 MessageResponseManager
 * Central handler for structured messages, visual/auditory feedback, and YAML-defined interactions.
 *
 * Supports:
 *  - AdminMessageModels -> adminMessages.yml
 *  - UserMessageModels -> userMessages.yml
 *  - GenericMessageModels-> genericMessages.yml
 */
public class MessageResponseManager {

    private static final Logger log = ConquestClans.getInstance().getLogger();
    private static String cachedPrefix = null;

    // ------------------ Generic Message Senders (Admin) ------------------

    public static void send(CommandSender sender, AdminMessageModels model) {
        send(sender, model, Collections.emptyMap());
    }
    public static void send(CommandSender sender, GenericMessageModels model) {
        send(sender, model, Collections.emptyMap());
    }
    public static void send(CommandSender sender, UserMessageModels model) {
        send(sender, model, Collections.emptyMap());
    }

    public static void send(Player player, AdminMessageModels model) {
        send(player, model, Collections.emptyMap());
    }

    public static void send(Player player, AdminMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (player == null || section == null) return;
        sendFormatted(player, section, placeholders);
        playEffects(player, section, placeholders);
    }

    public static void send(CommandSender sender, AdminMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (sender == null || section == null) return;
        sendFormatted(sender, section, placeholders);
        playEffects(sender, section, placeholders);
    }

    // ------------------ Generic Message Senders (User) ------------------

    public static void send(Player player, UserMessageModels model) {
        send(player, model, Collections.emptyMap());
    }

    public static void send(Player player, UserMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (player == null || section == null) return;
        sendFormatted(player, section, placeholders);
        playEffects(player, section, placeholders);
    }

    public static void send(CommandSender sender, UserMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (sender == null || section == null) return;
        sendFormatted(sender, section, placeholders);
        playEffects(sender, section, placeholders);
    }

    // ------------------ Generic Message Senders (Generic) ------------------

    public static void send(Player player, GenericMessageModels model) {
        send(player, model, Collections.emptyMap());
    }

    public static void send(Player player, GenericMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (player == null || section == null) return;
        sendFormatted(player, section, placeholders);
        playEffects(player, section, placeholders);
    }

    public static void send(CommandSender sender, GenericMessageModels model, Map<String, String> placeholders) {
        ConfigurationSection section = getMessageSection(model);
        if (sender == null || section == null) return;
        sendFormatted(sender, section, placeholders);
        playEffects(sender, section, placeholders);
    }

    // ------------------ Help Page Senders ------------------

    public static void sendAdminHelpPage(Player player, String key, int page) {
        sendHelpFrom(player, AdminMessagesFile::getSection, key, page);
    }

    public static void sendUserHelpPage(Player player, String key, int page) {
        sendHelpFrom(player, UserMessagesFile::getSection, key, page);
    }

    private static void sendHelpFrom(
            Player player,
            java.util.function.Function<String, ConfigurationSection> resolver,
            String key,
            int page
    ) {
        ConfigurationSection section = resolver.apply(key);
        if (section == null) {
            log.warning("⚠️  Help section missing: " + key);
            return;
        }

        List<Map<?, ?>> allComponents = section.getMapList("components");
        List<Map<?, ?>> visible = new ArrayList<>();
        for (Map<?, ?> raw : allComponents) {
            String perm = (String) raw.get("permission");
            if (perm == null || perm.isBlank() || player.hasPermission(perm)) visible.add(raw);
        }

        int perPage = 7;
        int maxPage = Math.max(1, (int) Math.ceil(visible.size() / (double) perPage));
        page = Math.max(1, Math.min(page, maxPage));

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, visible.size());
        List<Map<?, ?>> pageComponents = visible.subList(start, end);

        if (section.isList("text")) {
            List<String> lines = section.getStringList("text");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == lines.size() - 2) line += " <gray>(Page " + page + "/" + maxPage + ")";
                player.sendMessage(PlaceholderAPIManager.parse(player, line));
            }
        }

        for (Map<?, ?> map : pageComponents) {
            Component comp = ComponentSerializerManager.deserializeComponent(map, player, Map.of(
                    "page", String.valueOf(page),
                    "max", String.valueOf(maxPage)
            ));
            player.sendMessage(comp);
        }

        player.sendMessage(Component.empty());
        playEffects(player, section, Map.of("page", String.valueOf(page), "max", String.valueOf(maxPage)));
    }

    // ------------------ Internal Message Handlers ------------------

    private static void sendFormatted(CommandSender sender, ConfigurationSection section, Map<String, String> placeholders) {
        if (section == null) return;

        List<String> lines = section.getStringList("text");
        String hover = section.getString("hover", "");
        String click = section.getString("click", "");
        String clickTypeRaw = section.getString("clickType", "RUN_COMMAND");
        boolean showPrefix = section.getBoolean("prefix", true);
        Component prefixComponent = showPrefix ? getPrefixComponent() : Component.empty();

        // Map "NONE" (or invalid) to no click event
        ClickEvent.Action clickAction = null;
        try {
            if (!"NONE".equalsIgnoreCase(clickTypeRaw)) {
                clickAction = ClickEvent.Action.valueOf(clickTypeRaw.toUpperCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // leave clickAction as null -> no click event
        }

        if (lines.isEmpty()) return;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;

            Player player = (sender instanceof Player) ? (Player) sender : null;
            Component base = PlaceholderAPIManager.parse(player, line, placeholders);

            if (!hover.isBlank()) {
                base = base.hoverEvent(PlaceholderAPIManager.parse(player, hover, placeholders));
            }
            if (!click.isBlank() && clickAction != null) {
                String parsedClick = PlaceholderAPIManager.PlaceholderSet.applyToStatic(click, placeholders);
                base = base.clickEvent(ClickEvent.clickEvent(clickAction, parsedClick));
            }

            base = prefixComponent.append(base);
            sender.sendMessage(base);
        }
    }

    private static void playEffects(CommandSender sender, ConfigurationSection section, Map<String, String> placeholders) {
        if (!(sender instanceof Player player)) return;

        SoundResponseManager.play(player, section);
        ParticleResponseManager.play(player, section);
        BossBarResponseManager.send(player, section, placeholders);
        ActionBarResponseManager.send(player, section, placeholders);
        EffectResponseManager.send(player, section, placeholders);
        TitleResponseManager.send(player, section, placeholders);
    }

    // ------------------ Config Section Access ------------------

    private static ConfigurationSection getMessageSection(AdminMessageModels model) {
        ConfigurationSection section = AdminMessagesFile.getSection(model.getPath());
        if (section == null) log.warning("⚠️ Missing admin message section: " + model.getPath());
        return section;
    }

    private static ConfigurationSection getMessageSection(UserMessageModels model) {
        ConfigurationSection section = UserMessagesFile.getSection(model.getPath());
        if (section == null) log.warning("⚠️ Missing user message section: " + model.getPath());
        return section;
    }

    private static ConfigurationSection getMessageSection(GenericMessageModels model) {
        ConfigurationSection section = GenericMessagesFile.getSection(model.getPath());
        if (section == null) log.warning("⚠️ Missing generic message section: " + model.getPath());
        return section;
    }

    // ------------------ Prefix Utilities ------------------

    private static String getPrefix() {
        if (cachedPrefix == null) {
            cachedPrefix = ConquestClans.getInstance()
                    .getConfigurationManager()
                    .getConfig()
                    .getString("chat-prefix", "<gray>[ConquestClans]</gray> ");
        }
        return cachedPrefix;
    }

    private static Component getPrefixComponent() {
        return PlaceholderAPIManager.parse(null, getPrefix());
    }

    private static String getPrefixPlain() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(getPrefixComponent());
    }

    public static void resetPrefixCache() {
        cachedPrefix = null;
    }
}
