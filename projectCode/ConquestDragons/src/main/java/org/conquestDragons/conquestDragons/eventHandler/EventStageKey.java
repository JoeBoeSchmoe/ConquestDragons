package org.conquestDragons.conquestDragons.eventHandler;

/**
 * High-level stage key for a multi-phase event.
 *
 * INITIAL  : event just started
 * IN_BELLY : main middle phase
 * FINAL    : wrapping up / cleanup
 */
public enum EventStageKey {
    INITIAL,
    IN_BELLY,
    FINAL
}
