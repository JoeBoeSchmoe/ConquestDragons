package org.conquestDragons.conquestDragons.dragonHandler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultBossbarSettingsFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultBossbarSettingsFile.BossbarConfig;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventStageKey;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * InBellyBossbarManager
 *
 * Shows a SURVIVAL TIMER bossbar during the IN_BELLY stage.
 * - Uses bossbars.in-belly from defaultBossbarSettings.yml
 * - Progress is based on remaining in-belly time
 * - Visible only to players currently in the IN_BELLY world
 */
public final class InBellyBossbarManager {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static InBellyBossbarManager INSTANCE;

    public static InBellyBossbarManager getInstance() {
        return INSTANCE;
    }

    public static synchronized InBellyBossbarManager start(ConquestDragons plugin) {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        INSTANCE = new InBellyBossbarManager(plugin);
        plugin.getLogger().info("✅  InBellyBossbarManager started.");
        return INSTANCE;
    }

    public static synchronized void stop() {
        if (INSTANCE == null) return;
        INSTANCE.clearAll();
        INSTANCE.plugin.getLogger().info("✅  InBellyBossbarManager stopped.");
        INSTANCE = null;
    }

    // ----------------------------------------------------------------------
    // Fields
    // ----------------------------------------------------------------------

    private final ConquestDragons plugin;
    private final ConcurrentMap<String, InBellyBar> byEventId = new ConcurrentHashMap<>();

    private InBellyBossbarManager(ConquestDragons plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ----------------------------------------------------------------------
    // Public API (called from EventSequenceManager)
    // ----------------------------------------------------------------------

    /**
     * Start (or reset) the in-belly bar for this event.
     */
    public void startInBellyBar(EventModel event) {
        if (event == null) return;

        String eventId = event.id();

        // ✔ Correct API for belly duration
        Duration duration = event.inBellyDuration();
        if (duration == null || duration.isZero() || duration.isNegative()) {
            plugin.getLogger().warning("[ConquestDragons] IN_BELLY bossbar skipped for event "
                    + eventId + " (no valid inBellyDuration)");
            return;
        }

        // Stop any existing bar first
        stopInBellyBar(event);

        BossbarConfig cfg = DefaultBossbarSettingsFile.getInBellyDefaults();
        if (cfg == null || !cfg.enabled()) {
            plugin.getLogger().info("[ConquestDragons] IN_BELLY bossbar disabled by config.");
            return;
        }

        // Build initial title
        long totalSeconds = duration.getSeconds();
        String timeText = formatDurationShort(totalSeconds);

        String rawTitle = cfg.title();
        if (rawTitle.isEmpty()) {
            rawTitle = "<dark_red><bold>Inside the Maw</bold></dark_red> <gray>- Survive <yellow>{time_remaining}</yellow></gray>";
        }

        String mmTitle = rawTitle
                .replace("{time_remaining}", timeText)
                .replace("{event}", event.displayName())
                .replace("{stage}", EventStageKey.IN_BELLY.name());

        Component titleComponent = MINI_MESSAGE.deserialize(mmTitle);
        String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);

        BarStyle style = cfg.style() != null ? cfg.style() : BarStyle.SOLID;

        // ✔ BossBar initializes only with color, style, title
        BossBar bar = Bukkit.createBossBar(
                legacyTitle,
                BarColor.RED,
                style
        );
        bar.setVisible(true);
        bar.setProgress(1.0);

        // Duration window
        Instant now = Instant.now();
        Instant end = now.plus(duration);

        InBellyBar state = new InBellyBar(event, bar, end, duration);
        byEventId.put(eventId, state);

        // Tick
        long intervalTicks = cfg.updateIntervalTicks() > 0 ? cfg.updateIntervalTicks() : 5L;
        state.tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> tickSingle(state),
                intervalTicks,
                intervalTicks
        );

        plugin.getLogger().info("[ConquestDragons] IN_BELLY bossbar started for event "
                + eventId + " (duration=" + duration.getSeconds() + "s)");
    }

    /**
     * Stop and remove the in-belly bar for this event.
     */
    public void stopInBellyBar(EventModel event) {
        if (event == null) return;
        InBellyBar state = byEventId.remove(event.id());
        if (state == null) return;

        if (state.tickTask != null) {
            state.tickTask.cancel();
        }
        try {
            state.bar.removeAll();
            state.bar.setVisible(false);
        } catch (Exception ignored) {
        }
    }

    // Called when plugin disables or all events end
    private void clearAll() {
        for (Map.Entry<String, InBellyBar> e : byEventId.entrySet()) {
            InBellyBar state = e.getValue();
            if (state.tickTask != null) {
                state.tickTask.cancel();
            }
            try {
                state.bar.removeAll();
                state.bar.setVisible(false);
            } catch (Exception ignored) {
            }
        }
        byEventId.clear();
    }

    // ----------------------------------------------------------------------
    // Tick logic (per event)
    // ----------------------------------------------------------------------

    private void tickSingle(InBellyBar state) {
        EventModel event = state.event;
        if (event == null) {
            return;
        }

        // If event left IN_BELLY, just hide bar
        if (event.currentStageKey() != EventStageKey.IN_BELLY) {
            stopInBellyBar(event);
            return;
        }

        Instant now = Instant.now();
        long totalSeconds = state.totalDuration.getSeconds();
        long secondsRemaining = Math.max(0L, state.endInstant.getEpochSecond() - now.getEpochSecond());

        double fraction = totalSeconds > 0 ? (double) secondsRemaining / (double) totalSeconds : 0.0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        // Update bossbar progress
        state.bar.setProgress(fraction);

        // Update title with new {time_remaining}
        String timeText = formatDurationShort(secondsRemaining);

        BossbarConfig cfg = DefaultBossbarSettingsFile.getInBellyDefaults();
        String rawTitle = cfg != null && cfg.title() != null && !cfg.title().isEmpty()
                ? cfg.title()
                : "<dark_red><bold>Inside the Maw</bold></dark_red> <gray>- Survive <yellow>{time_remaining}</yellow></gray>";

        String mmTitle = rawTitle
                .replace("{time_remaining}", timeText)
                .replace("{event}", event.displayName())
                .replace("{stage}", EventStageKey.IN_BELLY.name());

        Component titleComponent = MINI_MESSAGE.deserialize(mmTitle);
        String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
        state.bar.setTitle(legacyTitle);

        // Update viewers: everyone in the IN_BELLY world
        updateViewersForInBelly(event, state);

        // Optionally: when timer hits 0, you might want to notify EventSequenceManager
        if (secondsRemaining <= 0L) {
            // Cosmetic: keep bar at 0 until EventSequenceManager actually ends the stage,
            // or you can immediately hide it:
            // stopInBellyBar(event);

            // If you want: call into EventSequenceManager hook:
            // EventSequenceManager mgr = EventSequenceManager.getInstance();
            // if (mgr != null) {
            //     mgr.onInBellyTimerExpired(event);
            // }
        }
    }

    private void updateViewersForInBelly(EventModel event, InBellyBar state) {
        state.bar.removeAll();

        Collection<UUID> participants = event.participantsSnapshot();
        if (participants == null || participants.isEmpty()) {
            return;
        }

        EventModel.StageArea bellyArea = event.stageAreaOrNull(EventStageKey.IN_BELLY);
        String bellyWorldName = null;
        if (bellyArea != null && bellyArea.spawn() != null && bellyArea.spawn().getWorld() != null) {
            bellyWorldName = bellyArea.spawn().getWorld().getName();
        }

        // Fallback → all participants
        if (bellyWorldName == null || bellyWorldName.isEmpty()) {
            for (UUID uuid : participants) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    state.bar.addPlayer(p);
                }
            }
            return;
        }

        // Normal path: only players in belly world
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            if (p.getWorld() == null) continue;

            if (p.getWorld().getName().equalsIgnoreCase(bellyWorldName)) {
                state.bar.addPlayer(p);
            }
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static String formatDurationShort(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long minutes = seconds / 60;
        long rem = seconds % 60;
        if (minutes > 0) {
            if (rem > 0) {
                return minutes + "m " + rem + "s";
            }
            return minutes + "m";
        }
        return seconds + "s";
    }

    // ----------------------------------------------------------------------
    // Inner state object
    // ----------------------------------------------------------------------

    private static final class InBellyBar {
        final EventModel event;
        final BossBar bar;
        final Instant endInstant;
        final Duration totalDuration;
        BukkitTask tickTask;

        InBellyBar(EventModel event,
                   BossBar bar,
                   Instant endInstant,
                   Duration totalDuration) {
            this.event = event;
            this.bar = bar;
            this.endInstant = endInstant;
            this.totalDuration = totalDuration;
        }
    }
}
