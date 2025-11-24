package org.conquestDragons.conquestDragons.eventHandler;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * EventRewardExecutor
 *
 * Responsibilities:
 *  - Roll universal completion rewards for all participants of an EventModel.
 *  - Roll rank-specific rewards for top damage dealers using EventModel.topDamage(...).
 *  - Dispatch configured commands as console with simple placeholder resolution.
 *
 * Notes:
 *  - Rewards are driven entirely by EventModel.RewardSpec and RankingRewardSpec.
 *  - This class does NOT decide when to run; that is owned by EventSequenceManager.
 */
public final class EventRewardExecutor {

    private EventRewardExecutor() {
        // utility class
    }

    // ---------------------------------------------------
    // Public API
    // ---------------------------------------------------

    /**
     * Grant universal completion rewards to all participants in this event.
     *
     * For each participant:
     *  - Iterate EventModel.completionRewards()
     *  - For each RewardSpec, roll chancePercent and run commands if it hits.
     */
    public static void grantCompletionRewards(EventModel event) {
        if (event == null) {
            return;
        }

        Logger log = Bukkit.getLogger();
        ConsoleCommandSender console = Bukkit.getConsoleSender();

        Collection<UUID> participants = event.participantsSnapshot();
        if (participants.isEmpty()) {
            log.info("[ConquestDragons] [EventRewardExecutor] No participants to reward for event " + event.id());
            return;
        }

        List<EventModel.RewardSpec> rewards = event.completionRewards();
        if (rewards.isEmpty()) {
            log.info("[ConquestDragons] [EventRewardExecutor] No completion rewards configured for event " + event.id());
            return;
        }

        log.info("[ConquestDragons] [EventRewardExecutor] Granting completion rewards for event "
                + event.id() + " to " + participants.size() + " participants.");

        for (UUID uuid : participants) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline.getName();
            if (name == null || name.isBlank()) {
                // skip nameless entries; you could also log if you want.
                continue;
            }
            // For online players you might add extra effects; for now, name is enough for commands.
            for (EventModel.RewardSpec spec : rewards) {
                rollAndExecuteReward(spec, name, console, log, event.id(), "completion");
            }
        }
    }

    /**
     * Grant rank-based rewards to top damage dealers.
     *
     * - Uses EventModel.topDamage(maxRanks) to get final leaderboard.
     * - For each leaderboard rank, finds all RankingRewardSpec that apply to that rank.
     * - Rolls each spec's chance and runs commands for that player when it hits.
     *
     * @param event     event whose damage ledger and ranking rewards will be used
     * @param maxRanks  maximum number of ranks to reward (e.g. 10)
     */
    public static void grantRankingRewards(EventModel event, int maxRanks) {
        if (event == null || maxRanks <= 0) {
            return;
        }

        Logger log = Bukkit.getLogger();
        ConsoleCommandSender console = Bukkit.getConsoleSender();

        List<Map.Entry<UUID, Double>> leaderboard = event.topDamage(maxRanks);
        if (leaderboard.isEmpty()) {
            log.info("[ConquestDragons] [EventRewardExecutor] No damage entries to rank for event " + event.id());
            return;
        }

        List<EventModel.RankingRewardSpec> rankSpecs = event.rankingRewards();
        if (rankSpecs.isEmpty()) {
            log.info("[ConquestDragons] [EventRewardExecutor] No ranking rewards configured for event " + event.id());
            return;
        }

        log.info("[ConquestDragons] [EventRewardExecutor] Granting ranking rewards for event "
                + event.id() + " (maxRanks=" + maxRanks + ", leaderboardSize=" + leaderboard.size() + ")");

        int rank = 1;
        for (Map.Entry<UUID, Double> entry : leaderboard) {
            UUID uuid = entry.getKey();

            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String name = offline.getName();
            if (name == null || name.isBlank()) {
                rank++;
                continue;
            }

            double damage = entry.getValue();
            log.info("[ConquestDragons] [EventRewardExecutor] Rank " + rank + " for event "
                    + event.id() + " is " + name + " (damage=" + damage + ")");

            for (EventModel.RankingRewardSpec spec : rankSpecs) {
                if (!spec.appliesToRank(rank)) {
                    continue;
                }
                rollAndExecuteRankingReward(spec, name, rank, console, log, event.id());
            }

            rank++;
        }
    }

    // ---------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------

    /**
     * Roll a completion RewardSpec and, on success, run all commands for the given player.
     */
    private static void rollAndExecuteReward(EventModel.RewardSpec spec,
                                             String playerName,
                                             ConsoleCommandSender console,
                                             Logger log,
                                             String eventId,
                                             String categoryLabel) {

        double chance = spec.chanceNormalized();
        if (!randomRoll(chance)) {
            // missed roll; nothing to do
            return;
        }

        for (String raw : spec.commands()) {
            String cmd = applyPlaceholders(raw, playerName);
            if (cmd == null || cmd.isBlank()) {
                continue;
            }

            log.info("[ConquestDragons] [EventRewardExecutor] Executing " + categoryLabel
                    + " reward command for event=" + eventId
                    + ", player=" + playerName
                    + " -> " + cmd);

            Bukkit.dispatchCommand(console, cmd);
        }
    }

    /**
     * Roll a ranking RankingRewardSpec and, on success, run all commands for the given player.
     */
    private static void rollAndExecuteRankingReward(EventModel.RankingRewardSpec spec,
                                                    String playerName,
                                                    int rank,
                                                    ConsoleCommandSender console,
                                                    Logger log,
                                                    String eventId) {

        double chance = spec.chanceNormalized();
        if (!randomRoll(chance)) {
            // missed roll; nothing to do
            return;
        }

        for (String raw : spec.commands()) {
            String cmd = applyPlaceholders(raw, playerName);
            if (cmd == null || cmd.isBlank()) {
                continue;
            }

            log.info("[ConquestDragons] [EventRewardExecutor] Executing ranking reward command for event="
                    + eventId + ", player=" + playerName + ", rank=" + rank + " -> " + cmd);

            Bukkit.dispatchCommand(console, cmd);
        }
    }

    /**
     * Simple [0.0–1.0] random roll using ThreadLocalRandom.
     */
    private static boolean randomRoll(double chanceNormalized) {
        if (chanceNormalized <= 0.0) {
            return false;
        }
        if (chanceNormalized >= 1.0) {
            return true;
        }
        double roll = ThreadLocalRandom.current().nextDouble();
        return roll <= chanceNormalized;
    }

    /**
     * Minimal placeholder resolver for reward commands.
     *
     * Currently supported:
     *  - {player} -> player's name
     *
     * You can extend this in the future to support more placeholders.
     */
    private static String applyPlaceholders(String raw, String playerName) {
        if (raw == null) {
            return null;
        }
        return raw.replace("{player}", playerName);
    }
}
