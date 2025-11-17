package org.conquestDragons.conquestDragons.eventHandler;

/**
 * High-level stage key for the multi-phase dragon event.
 *
 * Conceptually:
 *
 *  - LOBBY:
 *      Global pre-combat stage (Stage 0).
 *      The event is announced, the join window is open, and players who join
 *      are placed into the lobby region + spawn.
 *      Here you can:
 *        - Show countdown / info
 *        - Keep players safe from damage
 *        - Prevent them from interfering with the arena early
 *
 *  - INITIAL:
 *      Global combat stage 1.
 *      The main wave of dragons is active in the arena.
 *      Players transition from LOBBY → INITIAL when the join window ends,
 *      or when you decide to start the fight.
 *
 *  - IN_BELLY:
 *      Per-player sub-phase during INITIAL.
 *      A player has been "eaten" by a specific dragon and is inside the belly,
 *      doing belly mechanics. The global event can still be in INITIAL while
 *      this happens.
 *
 *  - POST_BELLY:
 *      Per-player state indicating they have escaped the belly and are back
 *      in the arena, still participating in killing the same INITIAL dragons.
 *
 *      A player can reach POST_BELLY in two ways:
 *        1) They complete the belly mechanics and are released.
 *        2) The dragon that ate them is killed by other players outside,
 *           which forcibly ejects them early.
 *
 *  - FINAL:
 *      Global stage 2 (boss / final mechanics).
 *      Triggered only when:
 *        - All INITIAL dragons are dead, AND
 *        - All participants are at least in POST_BELLY (no one still IN_BELLY).
 *
 *      In FINAL, the boss dragon and/or final mechanics begin.
 */
public enum EventStageKey {

    /** Stage 0: waiting room while join window is open. */
    LOBBY,

    /** Stage 1: main dragon wave in the arena. */
    INITIAL,

    /** Per-player sub-phase: inside the dragon’s belly. */
    IN_BELLY,

    /** Per-player: escaped belly, finishing off remaining INITIAL dragons. */
    POST_BELLY,

    /** Stage 2: final boss / climactic phase. */
    FINAL
}
