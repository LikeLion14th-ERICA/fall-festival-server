package dev.espero.festival.account;

/** Safe outcome used by CLI output and metrics. */
public record OperationalAccountChangeResult(
    OperationalAccountChangeAction action,
    OperationalAccountSetting before,
    OperationalAccountSetting setting,
    boolean changed
) {

    @Override
    public String toString() {
        return "OperationalAccountChangeResult[action=" + action + ", purpose=" + setting.purpose()
            + ", version=" + setting.version() + ", changed=" + changed + ", values=[REDACTED]]";
    }
}
