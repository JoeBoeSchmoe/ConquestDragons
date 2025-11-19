package org.conquestDragons.conquestDragons.dragonHandler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.conquestDragons.conquestDragons.ConquestDragons;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultBossbarSettingsFile;
import org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.defaultValueFiles.DefaultBossbarSettingsFile.BossbarConfig;
import org.conquestDragons.conquestDragons.dragonHandler.keyHandler.DragonGlowColorHealthKey;
import org.conquestDragons.conquestDragons.eventHandler.EventModel;
import org.conquestDragons.conquestDragons.eventHandler.EventSequenceManager;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * DragonBossbarManager
 *
 * Responsibilities:
 *  - Track active event dragons and create a BossBar per dragon.
 *  - Update BossBar progress + color based on current health.
 *  - Show BossBar only to players within the event's dragonRegion
 *    (for now: all event participants inside region).
 *  - Update glow color (via scoreboard team color) based on remaining health.
 *
 * This manager is driven by a repeating task, not by vanilla dragon battle.
 */
public final class DragonBossbarManager implements Listener {

    // ---------------------------------------------------
    // Singleton
    // ---------------------------------------------------

    private static DragonBossbarManager INSTANCE;

    public static DragonBossbarManager getInstance() {
        return INSTANCE;
    }

    public static synchronized DragonBossbarManager start(ConquestDragons plugin) {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        INSTANCE = new DragonBossbarManager(plugin);
        INSTANCE.startTickTask();
        plugin.getServer().getPluginManager().registerEvents(INSTANCE, plugin);
        plugin.getLogger().info("✅  DragonBossbarManager started.");
        return INSTANCE;
    }

    public static synchronized void stop() {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.stopTickTask();
        INSTANCE.clearAll();
        INSTANCE.plugin.getLogger().info("✅  DragonBossbarManager stopped.");
        INSTANCE = null;
    }

    // ---------------------------------------------------
    // Fields
    // ---------------------------------------------------

    private final ConquestDragons plugin;
    private final ConcurrentMap<UUID, TrackedDragon> tracked = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    private DragonBossbarManager(ConquestDragons plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ---------------------------------------------------
    // Public API
    // ---------------------------------------------------

    /**
     * Register an event dragon to be tracked for bossbar/glow.
     * Called when a dragon is spawned for an EventModel.
     */
    public void trackDragon(EventModel event, EnderDragon dragon) {
        if (event == null || dragon == null) {
            return;
        }

        UUID id = dragon.getUniqueId();

        // Don't double-track.
        if (tracked.containsKey(id)) {
            return;
        }

        try {
            TrackedDragon td = createTrackedDragon(event, dragon);
            tracked.put(id, td);
        } catch (Exception ex) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[ConquestDragons] Failed to create bossbar for dragon " + dragon.getUniqueId(),
                    ex
            );
        }
    }

    // ---------------------------------------------------
    // Tick lifecycle
    // ---------------------------------------------------

    private void startTickTask() {
        if (tickTask != null) {
            return;
        }
        // 10 ticks ~ 0.5s; frequent enough for smooth bars / glow updates
        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                10L
        );
    }

    private void stopTickTask() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        tickTask = null;
    }

    private void clearAll() {
        for (TrackedDragon td : tracked.values()) {
            try {
                td.bossBar.removeAll();
                td.bossBar.setVisible(false);
            } catch (Exception ignored) {
            }
        }
        tracked.clear();
    }

    private void tick() {
        if (tracked.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, TrackedDragon>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TrackedDragon> entry = it.next();
            TrackedDragon td = entry.getValue();
            EnderDragon dragon = td.dragon;

            // Clean up dead / invalid dragons
            if (dragon == null || dragon.isDead() || !dragon.isValid()) {
                // If this dragon had triggered belly, it may have eaten players.
                // Notify EventSequenceManager so it can prematurely free them.
                if (td.bellyTriggerFired) {
                    EventSequenceManager mgr = EventSequenceManager.getInstance();
                    if (mgr != null && td.event != null && dragon != null) {
                        mgr.onDragonKilled(td.event, dragon);
                    }
                }

                td.bossBar.removeAll();
                td.bossBar.setVisible(false);
                it.remove();
                continue;
            }

            updateBossbarAndGlow(td);
        }
    }

    // ---------------------------------------------------
    // Core update logic
    // ---------------------------------------------------

    private void updateBossbarAndGlow(TrackedDragon td) {
        EnderDragon dragon = td.dragon;

        double maxHealth = td.maxHealth;
        if (maxHealth <= 0.0) {
            maxHealth = 1.0;
        }

        double currentHealth = Math.max(0.0, Math.min(dragon.getHealth(), maxHealth));
        double fraction = currentHealth / maxHealth;

        td.bossBar.setProgress(clamp01(fraction));

        // Bossbar color based on remaining health (style is config-driven).
        BarColor newColor = pickBossbarColor(td.bossbarProfile, fraction);
        if (newColor != td.currentColor) {
            td.currentColor = newColor;
            td.bossBar.setColor(newColor);
        }

        updateViewers(td);
        updateGlowColor(td, fraction);

        // 🔥 Belly trigger detection (once per dragon)
        double trigger = td.event.bellyTriggerHealthFraction();
        if (!td.bellyTriggerFired && trigger > 0.0 && trigger < 1.0 && fraction <= trigger) {
            td.bellyTriggerFired = true;
            EventSequenceManager mgr = EventSequenceManager.getInstance();
            if (mgr != null) {
                mgr.onDragonBellyTrigger(td.event, dragon, fraction);
            }
        }
    }

    private void updateViewers(TrackedDragon td) {
        td.bossBar.removeAll();

        EventModel event = td.event;
        EventModel.EventRegion region = event.dragonRegion();
        if (region == null) {
            return;
        }

        Collection<UUID> participants = event.participantsSnapshot();
        if (participants == null || participants.isEmpty()) {
            return;
        }

        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (!isInsideRegion(region, p.getLocation())) {
                continue;
            }

            // Region-based visibility; we still use event participants
            // as the source list for eligible viewers.
            td.bossBar.addPlayer(p);
        }
    }

    private void updateGlowColor(TrackedDragon td, double fraction) {
        EnderDragon dragon = td.dragon;

        // Ensure glowing is enabled; scoreboard team color controls edge color.
        if (!dragon.isGlowing()) {
            dragon.setGlowing(true);
        }

        GlowBand band = pickGlowBand(td.glowProfile, fraction);
        if (band == null) {
            return;
        }

        if (band.id().equals(td.currentGlowBand)) {
            return; // already in this band
        }
        td.currentGlowBand = band.id();

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = dragon.getUniqueId().toString();

        // Remove from any of our previous glow teams
        removeFromGlowTeams(board, entry);

        String teamName = "CD_DRAGON_GLOW_" + band.id();
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.setColor(band.color());
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        } else {
            team.setColor(band.color());
        }

        team.addEntry(entry);
    }

    private void removeFromGlowTeams(Scoreboard board, String entry) {
        for (Team t : board.getTeams()) {
            if (!t.getName().startsWith("CD_DRAGON_GLOW_")) {
                continue;
            }
            if (t.hasEntry(entry)) {
                t.removeEntry(entry);
            }
        }
    }

    // ---------------------------------------------------
    // Helpers
    // ---------------------------------------------------

    private TrackedDragon createTrackedDragon(EventModel event, EnderDragon dragon) {
        PersistentDataContainer pdc = dragon.getPersistentDataContainer();
        MiniMessage mm = MiniMessage.miniMessage();

        NamespacedKey bossbarProfileKey =
                new NamespacedKey(plugin, "dragon_bossbar_profile");
        NamespacedKey bossbarNameKey =
                new NamespacedKey(plugin, "dragon_bossbar_name_mm");
        NamespacedKey glowProfileKey =
                new NamespacedKey(plugin, "dragon_glow_profile");

        String bossbarProfileRaw = pdc.get(bossbarProfileKey, PersistentDataType.STRING);
        String bossbarNameRaw = pdc.get(bossbarNameKey, PersistentDataType.STRING);
        String glowProfileRaw = pdc.get(glowProfileKey, PersistentDataType.STRING);

        DragonGlowColorHealthKey bossbarProfile = DragonGlowColorHealthKey.SIMPLE;
        DragonGlowColorHealthKey glowProfile = DragonGlowColorHealthKey.SIMPLE;

        // Use fromConfig so lowercase / hyphen / spaces work.
        if (bossbarProfileRaw != null && !bossbarProfileRaw.isEmpty()) {
            try {
                bossbarProfile = DragonGlowColorHealthKey.fromConfig(bossbarProfileRaw);
            } catch (IllegalArgumentException ignored) {
                // keep SIMPLE fallback
            }
        }
        if (glowProfileRaw != null && !glowProfileRaw.isEmpty()) {
            try {
                glowProfile = DragonGlowColorHealthKey.fromConfig(glowProfileRaw);
            } catch (IllegalArgumentException ignored) {
                // keep SIMPLE fallback
            }
        }

        // -------------------------------------------------
        // Resolve bossbar settings from defaultBossbarSettings.yml
        // -------------------------------------------------
        BossbarConfig cfg = DefaultBossbarSettingsFile.getDragonDefaults();

        // Dragon display name in MiniMessage form (used for {dragon_name})
        String dragonNameMm = (bossbarNameRaw != null && !bossbarNameRaw.isEmpty())
                ? bossbarNameRaw
                : "<red>Dragon</red>";

        // Template from config (MiniMessage), with placeholder {dragon_name}
        String template = cfg.title();
        if (template == null || template.isEmpty()) {
            template = "{dragon_name}";
        }

        String resolvedTitleMm = template.replace("{dragon_name}", dragonNameMm);

        // Build Component via MiniMessage from the resolved template
        Component titleComponent = mm.deserialize(resolvedTitleMm);

        // Convert Component → legacy string for Bukkit boss bar
        String titleString = LegacyComponentSerializer.legacySection().serialize(titleComponent);

        // Create BossBar:
        //  - Color is TEMP (we immediately override based on health)
        //  - Style comes from config
        BossBar bar = Bukkit.createBossBar(
                titleString,
                BarColor.PURPLE,  // placeholder; real color is set by health logic
                cfg.style()
        );

        // Visibility from config.
        // NOTE: Bukkit BossBar does NOT support darken sky / fog / boss music flags;
        // those are only available on Adventure bossbars or NMS BossBattle.
        bar.setVisible(cfg.enabled());

        // Determine max health
        AttributeInstance attr = dragon.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = (attr != null) ? attr.getValue() : dragon.getHealth();
        if (maxHealth <= 0.0) {
            maxHealth = 1.0;
        }

        TrackedDragon td = new TrackedDragon(event, dragon, bar, bossbarProfile, glowProfile, maxHealth);
        // Initialize bar with full health
        bar.setProgress(1.0);
        bar.setColor(pickBossbarColor(bossbarProfile, 1.0));
        td.currentColor = bar.getColor();

        return td;
    }

    private static boolean isInsideRegion(EventModel.EventRegion region, Location loc) {
        if (region == null || loc == null) {
            return false;
        }
        Location min = region.cornerMin();
        Location max = region.cornerMax();
        if (min == null || max == null) {
            return false;
        }
        if (min.getWorld() == null || loc.getWorld() == null) {
            return false;
        }
        if (!min.getWorld().getName().equals(loc.getWorld().getName())) {
            return false;
        }

        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        double minX = Math.min(min.getX(), max.getX());
        double maxX = Math.max(min.getX(), max.getX());
        double minY = Math.min(min.getY(), max.getY());
        double maxY = Math.max(min.getY(), max.getY());
        double minZ = Math.min(min.getZ(), max.getZ());
        double maxZ = Math.max(min.getZ(), max.getZ());

        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /**
     * Count how many dragons are currently active for the given event.
     */
    public int countActiveDragonsForEvent(EventModel event) {
        if (event == null) return 0;
        String id = event.id();
        int count = 0;
        for (TrackedDragon td : tracked.values()) {
            EnderDragon d = td.dragon;
            if (d != null && d.isValid() && !d.isDead()
                    && td.event.id().equals(id)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------
    // Shared band resolver
    // ---------------------------------------------------

    /**
     * Shared health band resolver.
     *
     * Returns a small integer band index for a given (profile, fraction),
     * so bossbar and glow use IDENTICAL thresholds.
     *
     * SIMPLE:
     *   0 = HIGH
     *   1 = MID
     *   2 = LOW
     *
     * DETAILED:
     *   0 = 100–95%
     *   1 = 95–80%
     *   2 = 80–65%
     *   3 = 65–50%
     *   4 = 50–35%
     *   5 = 35–25%
     *   6 = 25–15%
     *   7 = 15–05%
     *   8 = 05–00%
     */
    private static int resolveHealthBandIndex(DragonGlowColorHealthKey profile, double fractionRaw) {
        double fraction = clamp01(fractionRaw);

        switch (profile) {
            case SIMPLE:
                if (fraction > 0.66) return 0; // HIGH
                if (fraction > 0.33) return 1; // MID
                return 2;                      // LOW

            case DETAILED:
                if (fraction > 0.95) return 0; // 100–95
                if (fraction > 0.80) return 1; // 95–80
                if (fraction > 0.65) return 2; // 80–65
                if (fraction > 0.50) return 3; // 65–50
                if (fraction > 0.35) return 4; // 50–35
                if (fraction > 0.25) return 5; // 35–25
                if (fraction > 0.15) return 6; // 25–15
                if (fraction > 0.05) return 7; // 15–05
                return 8;                      // 05–00

            default:
                // Fallback: treat unknown as SIMPLE
                if (fraction > 0.66) return 0;
                if (fraction > 0.33) return 1;
                return 2;
        }
    }

    // ---------------------------------------------------
    // Bossbar color mapping (from band index)
    // ---------------------------------------------------

    private static BarColor pickBossbarColor(DragonGlowColorHealthKey profile, double fraction) {
        int band = resolveHealthBandIndex(profile, fraction);

        switch (profile) {
            case SIMPLE:
                // 0 = HIGH, 1 = MID, 2 = LOW
                return switch (band) {
                    case 0 -> BarColor.GREEN;   // HIGH
                    case 1 -> BarColor.YELLOW;  // MID
                    default -> BarColor.RED;    // LOW
                };

            case DETAILED:
                // Match glow bands as closely as BarColor allows.
                // Glow:
                //  0: DARK_GREEN
                //  1: GREEN
                //  2: DARK_AQUA
                //  3: AQUA
                //  4: YELLOW
                //  5: GOLD
                //  6: RED
                //  7: DARK_RED
                //  8: DARK_PURPLE
                return switch (band) {
                    case 0 -> BarColor.GREEN;   // DARK_GREEN  → GREEN
                    case 1 -> BarColor.GREEN;   // GREEN       → GREEN
                    case 2 -> BarColor.BLUE;    // DARK_AQUA   → BLUE
                    case 3 -> BarColor.BLUE;    // AQUA        → BLUE
                    case 4 -> BarColor.YELLOW;  // YELLOW      → YELLOW
                    case 5 -> BarColor.YELLOW;  // GOLD        → YELLOW
                    case 6 -> BarColor.RED;     // RED         → RED
                    case 7 -> BarColor.RED;     // DARK_RED    → RED
                    default -> BarColor.PURPLE; // DARK_PURPLE → PURPLE
                };

            default:
                // Safety fallback → SIMPLE mapping
                return switch (band) {
                    case 0 -> BarColor.GREEN;
                    case 1 -> BarColor.YELLOW;
                    default -> BarColor.RED;
                };
        }
    }

    // ---------------------------------------------------
    // Glow band mapping (from band index)
    // ---------------------------------------------------

    /**
     * Represents a glow "band": id used in team name + ChatColor.
     * This lets us keep distinct teams for SIMPLE vs DETAILED bands.
     */
    private record GlowBand(String id, ChatColor color) { }

    private static GlowBand pickGlowBand(DragonGlowColorHealthKey profile, double fraction) {
        int band = resolveHealthBandIndex(profile, fraction);

        switch (profile) {
            case SIMPLE:
                // 0 = HIGH, 1 = MID, 2 = LOW
                return switch (band) {
                    case 0 -> new GlowBand("SIMPLE_HIGH", ChatColor.GREEN);
                    case 1 -> new GlowBand("SIMPLE_MID", ChatColor.YELLOW);
                    default -> new GlowBand("SIMPLE_LOW", ChatColor.RED);
                };

            case DETAILED:
                // Band indices 0–8 map to your detailed colors.
                return switch (band) {
                    case 0 -> new GlowBand("DET_100_95", ChatColor.DARK_GREEN);
                    case 1 -> new GlowBand("DET_95_80", ChatColor.GREEN);
                    case 2 -> new GlowBand("DET_80_65", ChatColor.DARK_AQUA);
                    case 3 -> new GlowBand("DET_65_50", ChatColor.AQUA);
                    case 4 -> new GlowBand("DET_50_35", ChatColor.YELLOW);
                    case 5 -> new GlowBand("DET_35_25", ChatColor.GOLD);
                    case 6 -> new GlowBand("DET_25_15", ChatColor.RED);
                    case 7 -> new GlowBand("DET_15_05", ChatColor.DARK_RED);
                    default -> new GlowBand("DET_05_00", ChatColor.DARK_PURPLE);
                };

            default:
                // Fallback → SIMPLE mapping
                return switch (band) {
                    case 0 -> new GlowBand("SIMPLE_HIGH", ChatColor.GREEN);
                    case 1 -> new GlowBand("SIMPLE_MID", ChatColor.YELLOW);
                    default -> new GlowBand("SIMPLE_LOW", ChatColor.RED);
                };
        }
    }

    // ---------------------------------------------------
    // Inner value object
    // ---------------------------------------------------

    private static final class TrackedDragon {
        final EventModel event;
        final EnderDragon dragon;
        final BossBar bossBar;
        final DragonGlowColorHealthKey bossbarProfile;
        final DragonGlowColorHealthKey glowProfile;
        final double maxHealth;

        BarColor currentColor;
        String currentGlowBand;
        boolean bellyTriggerFired;

        TrackedDragon(EventModel event,
                      EnderDragon dragon,
                      BossBar bossBar,
                      DragonGlowColorHealthKey bossbarProfile,
                      DragonGlowColorHealthKey glowProfile,
                      double maxHealth) {
            this.event = Objects.requireNonNull(event, "event");
            this.dragon = Objects.requireNonNull(dragon, "dragon");
            this.bossBar = Objects.requireNonNull(bossBar, "bossBar");
            this.bossbarProfile = Objects.requireNonNull(bossbarProfile, "bossbarProfile");
            this.glowProfile = Objects.requireNonNull(glowProfile, "glowProfile");
            this.maxHealth = maxHealth;
        }
    }
}
