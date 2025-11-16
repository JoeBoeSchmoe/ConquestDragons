package org.conquestDragons.conquestDragons.eventHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration model for a single event stage.
 *
 * For a given {@link EventStageKey}, this model defines:
 *  - startCommands: run once when the stage begins
 *  - timedCommands: run at offsets (in ticks/seconds) after stage start
 *  - endCommands:   run once when the stage ends
 *
 * This is a pure data model; the actual execution (console/player, placeholder
 * expansion, scheduling) is handled elsewhere.
 */
public final class EventStageModel {

    private final EventStageKey stageKey;

    /** Commands executed once when this stage starts. */
    private final List<String> startCommands;

    /** Commands scheduled during this stage at specific offsets. */
    private final List<TimedCommandSpec> timedCommands;

    /** Commands executed once when this stage ends. */
    private final List<String> endCommands;

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    public EventStageModel(EventStageKey stageKey,
                           List<String> startCommands,
                           List<TimedCommandSpec> timedCommands,
                           List<String> endCommands) {
        this.stageKey = Objects.requireNonNull(stageKey, "stageKey");

        this.startCommands = unmodifiableCopy(startCommands);
        this.timedCommands = unmodifiableCopy(timedCommands);
        this.endCommands = unmodifiableCopy(endCommands);
    }

    private static <T> List<T> unmodifiableCopy(List<T> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    // ---------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------

    public EventStageKey stageKey() {
        return stageKey;
    }

    /** Commands to run when stage starts. */
    public List<String> startCommands() {
        return startCommands;
    }

    /** Timed commands to run at offsets after stage start. */
    public List<TimedCommandSpec> timedCommands() {
        return timedCommands;
    }

    /** Commands to run when stage ends. */
    public List<String> endCommands() {
        return endCommands;
    }

    // ---------------------------------------------------------------------
    // Nested timed-command spec
    // ---------------------------------------------------------------------

    /**
     * Immutable spec for one "batch" of timed commands.
     *
     * delayTicks: offset from stage start in ticks (20 = 1 second).
     * commands  : list of raw command strings to execute at that offset.
     */
    public static final class TimedCommandSpec {

        private final long delayTicks;
        private final List<String> commands;

        public TimedCommandSpec(long delayTicks, List<String> commands) {
            if (delayTicks < 0) {
                throw new IllegalArgumentException("delayTicks cannot be negative");
            }
            this.delayTicks = delayTicks;
            this.commands = unmodifiableCopy(commands);
        }

        public long delayTicks() {
            return delayTicks;
        }

        public List<String> commands() {
            return commands;
        }
    }
}
