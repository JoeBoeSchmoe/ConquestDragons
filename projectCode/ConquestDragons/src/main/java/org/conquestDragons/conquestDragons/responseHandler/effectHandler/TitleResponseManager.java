package org.conquestDragons.conquestDragons.responseHandler.effectHandler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles.PlaceholderAPIManager;

import java.time.Duration;
import java.util.Map;

/**
 * 🎬 TitleResponseManager
 * Sends MiniMessage-based titles/subtitles with simple timing config.
 *
 * Expected YAML shape:
 * title:
 *   text: "<gold><bold>Dragon Admin</bold></gold>"
 *   subtitle: "<gray>Use /dragons admin help to get started."
 *   timings: { fadeIn: 10, stay: 40, fadeOut: 20 } # ticks
 */
public class TitleResponseManager {

    public static void send(Player player, ConfigurationSection section, Map<String, String> placeholders) {
        if (player == null || section == null) return;

        ConfigurationSection titleSection = section.getConfigurationSection("title");
        if (titleSection == null) return;

        String rawTitle = titleSection.getString("text", "").trim();
        String rawSubtitle = titleSection.getString("subtitle", "").trim();
        if (rawTitle.isEmpty() && rawSubtitle.isEmpty()) return;

        Component title = rawTitle.isEmpty()
                ? Component.empty()
                : PlaceholderAPIManager.parse(player, rawTitle, placeholders);

        Component subtitle = rawSubtitle.isEmpty()
                ? Component.empty()
                : PlaceholderAPIManager.parse(player, rawSubtitle, placeholders);

        // Defaults in ticks (20t = 1s)
        int fadeIn = 10, stay = 40, fadeOut = 20;

        Object timingsObj = titleSection.get("timings");
        if (timingsObj instanceof ConfigurationSection timingCfg) {
            fadeIn = timingCfg.getInt("fadeIn", fadeIn);
            stay   = timingCfg.getInt("stay", stay);
            fadeOut= timingCfg.getInt("fadeOut", fadeOut);
        }

        if (!isEmpty(title) || !isEmpty(subtitle)) {
            player.clearTitle();
            player.showTitle(Title.title(
                    title,
                    subtitle,
                    Title.Times.times(
                            Duration.ofMillis(fadeIn * 50L),
                            Duration.ofMillis(stay * 50L),
                            Duration.ofMillis(fadeOut * 50L)
                    )
            ));
        }
    }

    private static boolean isEmpty(Component component) {
        return component == null
                || Component.empty().equals(component)
                || net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component).isBlank();
    }
}
