package io.github.editid0.primedrtp.api;

/** Why a random teleport was requested. */
public enum RtpCause {
    /** Player ran /rtp for themselves. */
    COMMAND,
    /** Staff/console sent another player. */
    COMMAND_OTHER,
    GUI,
    SIGN,
    FIRST_JOIN,
    RESPAWN,
    /** Another plugin via {@link PrimedRTPApi}. */
    API
}
