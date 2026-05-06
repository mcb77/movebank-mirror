package de.firetail.compat.movebank.mirror.cli;

/**
 * Distinct exit codes so cron / k8s probes / shell wrappers can react to
 * specific failure classes.
 */
final class ExitCode {
    static final int OK              = 0;
    static final int GENERIC_ERROR   = 1;
    static final int USAGE_ERROR     = 2;
    static final int AUTH_ERROR      = 3;
    static final int LOCK_HELD       = 4;
    static final int IO_ERROR        = 5;
    static final int INTERRUPTED     = 130; // by convention SIGINT = 128 + 2

    private ExitCode() {}
}
