package org.conquestDragons.conquestDragons.eventHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration model for a single event stage.
 *
 * Contains:
 *
 *  COMMAND PIPELINE
 *   • startCommands    – run once at stage start
 *   • timedCommands    – run at offsets after start
 *   • endCommands      – run once at stage end
 *
 *  MESSAGE PIPELINE
 *   • repeatMessage    – OPTIONAL repeating message fired during the stage
 *                        intervalTicks:
 *                          - 0  => disabled
 *                          - >0 => repeat every N ticks
 *
 * No message-id declared in YAML anymore.
 * It is auto-generated from the stage key:
 *
 *     messages.user.stages.<STAGE_KEY>.repeat
 */
public final class EventStageModel {

    private final EventStageKey stageKey;

    // ---------------------------------------------------------
    // Commands
    // ---------------------------------------------------------
    private final List<String> startCommands;
    private final List<TimedCommandSpec> timedCommands;
    private final List<String> endCommands;

    // ---------------------------------------------------------
    // ONE repeat message spec per stage (nullable)
    // ---------------------------------------------------------
    private final RepeatMessageSpec repeatMessage;

    // ---------------------------------------------------------
    // Construction
    // ---------------------------------------------------------

    /** Compatibility constructor (commands only). */
    public EventStageModel(EventStageKey stageKey,
                           List<String> startCommands,
                           List<TimedCommandSpec> timedCommands,
                           List<String> endCommands) {
        this(stageKey, startCommands, timedCommands, endCommands, null);
    }

    /** Full constructor including repeatMessage. */
    public EventStageModel(EventStageKey stageKey,
                           List<String> startCommands,
                           List<TimedCommandSpec> timedCommands,
                           List<String> endCommands,
                           RepeatMessageSpec repeatMessage) {

        this.stageKey = Objects.requireNonNull(stageKey, "stageKey");

        this.startCommands  = copy(startCommands);
        this.timedCommands  = copy(timedCommands);
        this.endCommands    = copy(endCommands);
        this.repeatMessage  = repeatMessage; // nullable
    }

    private static <T> List<T> copy(List<T> src) {
        if (src == null || src.isEmpty())
            return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    // ---------------------------------------------------------
    // Getters
    // ---------------------------------------------------------

    public EventStageKey stageKey() {
        return stageKey;
    }

    public List<String> startCommands() {
        return startCommands;
    }

    public List<TimedCommandSpec> timedCommands() {
        return timedCommands;
    }

    public List<String> endCommands() {
        return endCommands;
    }

    public RepeatMessageSpec repeatMessage() {
        return repeatMessage;
    }

    // ---------------------------------------------------------
    // Timed command spec (one–shot batches)
    // ---------------------------------------------------------
    public static final class TimedCommandSpec {

        private final long delayTicks;
        private final List<String> commands;

        public TimedCommandSpec(long delayTicks, List<String> commands) {
            if (delayTicks < 0)
                throw new IllegalArgumentException("delayTicks cannot be negative");

            this.delayTicks = delayTicks;
            this.commands   = copy(commands);
        }

        public long delayTicks() {
            return delayTicks;
        }

        public List<String> commands() {
            return commands;
        }
    }

    // ---------------------------------------------------------
    // Repeat message (OPTIONAL, loops by interval)
    // ---------------------------------------------------------
    public static final class RepeatMessageSpec {

        /**
         * Interval between message fires, in ticks.
         *
         *  0  => disabled
         *  >0 => repeat every N ticks
         */
        private final long intervalTicks;

        public RepeatMessageSpec(long intervalTicks) {
            if (intervalTicks < 0)
                throw new IllegalArgumentException("intervalTicks cannot be negative");

            this.intervalTicks = intervalTicks;
        }

        public long intervalTicks() {
            return intervalTicks;
        }

        /**
         * AUTO DERIVED messageId for this stage:
         *
         *   messages.user.stages.<STAGE_KEY>.repeat
         */
        public String deriveMessageId(EventStageKey key) {
            return "user.stages." + key.name().toLowerCase().replace("_", "-") + ".repeat";
        }

        /**
         * Convenience: returns true if this spec is effectively disabled.
         */
        public boolean isDisabled() {
            return intervalTicks == 0L;
        }
    }
}
