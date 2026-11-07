package io.github.editid0.swiftrtp.config;

/** Fatal configuration error; message is shown to the admin. */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
