package org.conquestDragons.conquestDragons.responseHandler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.conquestClans.conquestclans.configurationHandler.configurationFiles.integrationFiles.PlaceholderAPIManager;

import java.util.Map;

/**
 * 🧱 ComponentSerializerManager
 * Builds formatted, hoverable, and clickable components from YAML structures (ConquestClans).
 */
public class ComponentSerializerManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Parses a raw MiniMessage string with optional placeholders for a player.
     *
     * @param player       Player for PlaceholderAPI (may be null)
     * @param rawText      The MiniMessage-formatted text
     * @param placeholders Custom placeholders map {key -> value}
     * @return Parsed Component
     */
    public static Component format(Player player, String rawText, Map<String, String> placeholders) {
        if (rawText == null || rawText.isEmpty()) return Component.empty();

        String parsed = PlaceholderAPIManager.PlaceholderSet.applyToStatic(rawText, placeholders);
        parsed = PlaceholderAPIManager.parsePlaceholders(player, parsed);

        return MINI.deserialize(parsed);
    }

    /**
     * Deserializes a clickable & hoverable message component from a YAML-like map.
     *
     * Expected keys:
     * - text: String (MiniMessage)
     * - hover: String (MiniMessage) optional
     * - click: String (command or URL) optional
     * - clickType: String (RUN_COMMAND | SUGGEST_COMMAND | OPEN_URL | NONE)
     *
     * @param raw          Raw map node (e.g., from messages.yml "components" list)
     * @param player       Player for placeholder resolution
     * @param placeholders Custom placeholders
     * @return Fully built Component (never null)
     */
    public static Component deserializeComponent(Map<?, ?> raw, Player player, Map<String, String> placeholders) {
        String text = getString(raw, "text");
        if (text.isEmpty()) return Component.empty();

        String hover = getString(raw, "hover");
        String click = getString(raw, "click");
        String clickType = getString(raw, "clickType", "NONE");

        // 📜 Base text
        Component component = PlaceholderAPIManager.parse(player, text, placeholders);

        // 🖱️ Hover event
        if (!hover.isEmpty()) {
            Component hoverComponent = PlaceholderAPIManager.parse(player, hover, placeholders);
            component = component.hoverEvent(HoverEvent.showText(hoverComponent));
        }

        // 🖱️ Click event
        if (!click.isEmpty() && !"NONE".equalsIgnoreCase(clickType)) {
            String parsedClick = PlaceholderAPIManager.PlaceholderSet.applyToStatic(click, placeholders);

            switch (clickType.toUpperCase()) {
                case "RUN_COMMAND" -> component = component.clickEvent(ClickEvent.runCommand(parsedClick));
                case "SUGGEST_COMMAND" -> component = component.clickEvent(ClickEvent.suggestCommand(parsedClick));
                case "OPEN_URL" -> component = component.clickEvent(ClickEvent.openUrl(parsedClick));
                default -> { /* ignore unknown types */ }
            }
        }

        return component;
    }

    // ==========================
    // 📦 Internal helpers
    // ==========================

    private static String getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private static String getString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
