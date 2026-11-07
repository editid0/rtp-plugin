package io.github.editid0.swiftrtp;

/**
 * Lifecycle for plugin services. {@link SwiftRTP} constructs every service first (constructors must NOT touch other
 * services), then calls {@link #start()} in dependency order, {@link #reload()} after a successful config reload, and
 * {@link #stop()} in reverse order on disable.
 */
public interface Service {
    default void start() {}

    /** Called after {@code plugin.settings()} / messages were replaced by a reload. */
    default void reload() {}

    default void stop() {}
}
