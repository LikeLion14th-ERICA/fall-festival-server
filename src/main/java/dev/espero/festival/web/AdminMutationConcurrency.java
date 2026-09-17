package dev.espero.festival.web;

/** Concurrency rule declared by an administrator mutation endpoint. */
public enum AdminMutationConcurrency {
    /** The client must read the current representation and return its strong ETag. */
    IF_MATCH_REQUIRED,
    /** Intentional exception for state toggles that are last-write-wins by product decision. */
    LAST_WRITE_WINS
}
