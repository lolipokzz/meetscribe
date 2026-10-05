package io.meetscribe.core;

/** Proposed lifecycle states; transitions and persistence are still to be implemented. */
public enum MeetingState {
    CREATED,
    RECORDING,
    RECORDED,
    TRANSCRIBING,
    TRANSCRIBED,
    SUMMARIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}
