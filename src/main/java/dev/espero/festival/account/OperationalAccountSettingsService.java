package dev.espero.festival.account;

import dev.espero.festival.persistence.OperationalAccountStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional CLI-only writer for revision-independent operational account settings. */
@Service
@Profile("db")
public class OperationalAccountSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OperationalAccountSettingsService.class);
    private static final Pattern BANK_CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    private final OperationalAccountStore store;
    private final TransferLinkPolicy transferLinks;
    private final Clock clock;

    public OperationalAccountSettingsService(
        OperationalAccountStore store,
        TransferLinkPolicy transferLinks,
        Clock clock
    ) {
        this.store = store;
        this.transferLinks = transferLinks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<OperationalAccountSetting> findCurrent(UUID festivalId, OperationalAccountPurpose purpose) {
        return findCurrent(OperationalAccountTarget.festival(festivalId, purpose));
    }

    @Transactional(readOnly = true)
    public Optional<OperationalAccountSetting> findCurrent(OperationalAccountTarget target) {
        return store.findCurrent(Objects.requireNonNull(target, "Account target is required"));
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewSet(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountChange change,
        String suppliedLastFour
    ) {
        return previewSet(OperationalAccountTarget.festival(festivalId, purpose), expectedVersion, change, suppliedLastFour);
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewSet(
        OperationalAccountTarget target,
        long expectedVersion,
        OperationalAccountChange change,
        String suppliedLastFour
    ) {
        OperationalAccountChange normalized = normalize(target, change);
        requireLastFour(normalized, suppliedLastFour);
        return preview(OperationalAccountChangeAction.SET, target, expectedVersion, normalized);
    }

    @Transactional
    public OperationalAccountChangeResult set(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountChange change,
        String suppliedLastFour,
        OperationalAccountAuditMetadata audit
    ) {
        return set(OperationalAccountTarget.festival(festivalId, purpose), expectedVersion, change, suppliedLastFour, audit);
    }

    @Transactional
    public OperationalAccountChangeResult set(
        OperationalAccountTarget target,
        long expectedVersion,
        OperationalAccountChange change,
        String suppliedLastFour,
        OperationalAccountAuditMetadata audit
    ) {
        OperationalAccountChange normalized = normalize(target, change);
        requireLastFour(normalized, suppliedLastFour);
        return write(OperationalAccountChangeAction.SET, target, expectedVersion, normalized, audit);
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewClear(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion
    ) {
        return previewClear(OperationalAccountTarget.festival(festivalId, purpose), expectedVersion);
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewClear(OperationalAccountTarget target, long expectedVersion) {
        return preview(OperationalAccountChangeAction.CLEAR, target, expectedVersion, OperationalAccountChange.unconfigured());
    }

    @Transactional
    public OperationalAccountChangeResult clear(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountAuditMetadata audit
    ) {
        return clear(OperationalAccountTarget.festival(festivalId, purpose), expectedVersion, audit);
    }

    @Transactional
    public OperationalAccountChangeResult clear(
        OperationalAccountTarget target,
        long expectedVersion,
        OperationalAccountAuditMetadata audit
    ) {
        return write(
            OperationalAccountChangeAction.CLEAR, target, expectedVersion, OperationalAccountChange.unconfigured(), audit
        );
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewRestore(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        long sourceVersion,
        String suppliedLastFour
    ) {
        return previewRestore(
            OperationalAccountTarget.festival(festivalId, purpose), expectedVersion, sourceVersion, suppliedLastFour
        );
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewRestore(
        OperationalAccountTarget target,
        long expectedVersion,
        long sourceVersion,
        String suppliedLastFour
    ) {
        OperationalAccountChange restored = restoredChange(target, sourceVersion);
        requireLastFour(restored, suppliedLastFour);
        return preview(OperationalAccountChangeAction.RESTORE, target, expectedVersion, restored);
    }

    @Transactional
    public OperationalAccountChangeResult restore(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        long sourceVersion,
        String suppliedLastFour,
        OperationalAccountAuditMetadata audit
    ) {
        return restore(
            OperationalAccountTarget.festival(festivalId, purpose), expectedVersion, sourceVersion, suppliedLastFour, audit
        );
    }

    @Transactional
    public OperationalAccountChangeResult restore(
        OperationalAccountTarget target,
        long expectedVersion,
        long sourceVersion,
        String suppliedLastFour,
        OperationalAccountAuditMetadata audit
    ) {
        OperationalAccountChange restored = restoredChange(target, sourceVersion);
        requireLastFour(restored, suppliedLastFour);
        return write(OperationalAccountChangeAction.RESTORE, target, expectedVersion, restored, audit);
    }

    private OperationalAccountChange restoredChange(OperationalAccountTarget target, long sourceVersion) {
        if (sourceVersion < 1) {
            throw new OperationalAccountException("ACCOUNT_HISTORY_VERSION_INVALID");
        }
        OperationalAccountHistory history = store.findHistory(
            Objects.requireNonNull(target, "Account target is required"), sourceVersion
        ).orElseThrow(() -> new OperationalAccountException("ACCOUNT_HISTORY_VERSION_NOT_FOUND"));
        return normalize(target, history.restoredChange());
    }

    private OperationalAccountChangeResult preview(
        OperationalAccountChangeAction action,
        OperationalAccountTarget target,
        long expectedVersion,
        OperationalAccountChange desired
    ) {
        Optional<OperationalAccountSetting> current = store.findCurrent(
            Objects.requireNonNull(target, "Account target is required")
        );
        verifyExpectedVersion(current, expectedVersion);
        if (current.filter(setting -> sameValues(setting, desired)).isPresent()) {
            return new OperationalAccountChangeResult(action, current.orElseThrow(), current.orElseThrow(), false);
        }
        return new OperationalAccountChangeResult(action, current.orElse(null), nextSetting(target, current, desired), true);
    }

    private OperationalAccountChangeResult write(
        OperationalAccountChangeAction action,
        OperationalAccountTarget target,
        long expectedVersion,
        OperationalAccountChange desired,
        OperationalAccountAuditMetadata audit
    ) {
        Objects.requireNonNull(target, "Account target is required");
        Objects.requireNonNull(audit, "Audit metadata is required");
        Optional<OperationalAccountSetting> current = store.findCurrentForUpdate(target);
        verifyExpectedVersion(current, expectedVersion);
        if (current.filter(setting -> sameValues(setting, desired)).isPresent()) {
            return new OperationalAccountChangeResult(action, current.orElseThrow(), current.orElseThrow(), false);
        }

        OperationalAccountSetting next = nextSetting(target, current, desired);
        store.setAuditMetadata(action, audit);
        try {
            if (current.isEmpty()) {
                store.insert(next);
            } else if (!store.update(next, expectedVersion)) {
                throw new OperationalAccountException("ACCOUNT_EXPECTED_VERSION_MISMATCH");
            }
        } catch (DuplicateKeyException exception) {
            throw new OperationalAccountException("ACCOUNT_EXPECTED_VERSION_MISMATCH");
        }
        logAfterCommit(next);
        return new OperationalAccountChangeResult(action, current.orElse(null), next, true);
    }

    private OperationalAccountSetting nextSetting(
        OperationalAccountTarget target,
        Optional<OperationalAccountSetting> current,
        OperationalAccountChange desired
    ) {
        long nextVersion = current.map(OperationalAccountSetting::version)
            .map(version -> version + 1)
            .orElseGet(() -> store.nextVersion(target));
        return new OperationalAccountSetting(
            target.festivalId(),
            target.purpose(),
            desired.state(),
            nextVersion,
            desired.bankName(),
            desired.accountNumber(),
            desired.accountHolder(),
            desired.transferLinkUrl(),
            clock.instant(),
            target.spaceId(),
            desired.bankCode(),
            desired.tossLinkEnabled()
        );
    }

    /**
     * A booth (SPACE) account carries the service's bank identifier and the
     * Toss shortcut switch but never a transfer link; festival-wide accounts
     * carry neither booth field.
     */
    private OperationalAccountChange normalize(OperationalAccountTarget target, OperationalAccountChange change) {
        Objects.requireNonNull(change, "Operational account change is required");
        if (change.state() == OperationalAccountState.UNCONFIGURED) {
            return OperationalAccountChange.unconfigured();
        }
        boolean space = target.purpose() == OperationalAccountPurpose.SPACE;
        if (space && change.transferLinkUrl() != null) {
            throw new OperationalAccountException("ACCOUNT_TRANSFER_LINK_NOT_ALLOWED");
        }
        if (!space && (change.bankCode() != null || change.tossLinkEnabled())) {
            throw new OperationalAccountException("ACCOUNT_SPACE_FIELDS_NOT_ALLOWED");
        }
        return new OperationalAccountChange(
            OperationalAccountState.CONFIGURED,
            requiredText(change.bankName(), "ACCOUNT_BANK_NAME_INVALID", 100),
            accountNumber(change.accountNumber()),
            requiredText(change.accountHolder(), "ACCOUNT_HOLDER_INVALID", 100),
            space ? null : transferLinks.validateOptional(change.transferLinkUrl()),
            space ? bankCode(change.bankCode()) : null,
            space && change.tossLinkEnabled()
        );
    }

    private String bankCode(String value) {
        if (value == null || !BANK_CODE.matcher(value.strip()).matches()) {
            throw new OperationalAccountException("ACCOUNT_BANK_ID_INVALID");
        }
        return value.strip();
    }

    private String accountNumber(String value) {
        String normalized = requiredText(value, "ACCOUNT_NUMBER_INVALID", 128);
        String digits = normalized.replaceAll("\\D", "");
        if (digits.length() < 4) {
            throw new OperationalAccountException("ACCOUNT_NUMBER_INVALID");
        }
        return normalized;
    }

    private void requireLastFour(OperationalAccountChange change, String suppliedLastFour) {
        if (change.state() == OperationalAccountState.UNCONFIGURED) {
            return;
        }
        if (suppliedLastFour == null || !suppliedLastFour.matches("[0-9]{4}")) {
            throw new OperationalAccountException("ACCOUNT_LAST_FOUR_INVALID");
        }
        String digits = change.accountNumber().replaceAll("\\D", "");
        if (!digits.endsWith(suppliedLastFour)) {
            throw new OperationalAccountException("ACCOUNT_LAST_FOUR_MISMATCH");
        }
    }

    private void verifyExpectedVersion(Optional<OperationalAccountSetting> current, long expectedVersion) {
        if (expectedVersion < 0 || current.map(OperationalAccountSetting::version).orElse(0L) != expectedVersion) {
            throw new OperationalAccountException("ACCOUNT_EXPECTED_VERSION_MISMATCH");
        }
    }

    private boolean sameValues(OperationalAccountSetting current, OperationalAccountChange desired) {
        return current.state() == desired.state()
            && Objects.equals(current.bankName(), desired.bankName())
            && Objects.equals(current.accountNumber(), desired.accountNumber())
            && Objects.equals(current.accountHolder(), desired.accountHolder())
            && Objects.equals(current.transferLinkUrl(), desired.transferLinkUrl())
            && Objects.equals(current.bankCode(), desired.bankCode())
            && current.tossLinkEnabled() == desired.tossLinkEnabled();
    }

    private String requiredText(String value, String code, int maximumLength) {
        if (value == null) {
            throw new OperationalAccountException(code);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
            || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new OperationalAccountException(code);
        }
        return normalized;
    }

    private void logAfterCommit(OperationalAccountSetting setting) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info(
                    "operational_account_changed purpose={} space_id={} version={} occurred_at={} change_count=1",
                    setting.purpose(), setting.spaceId() == null ? "-" : setting.spaceId(), setting.version(),
                    setting.updatedAt()
                );
            }
        });
    }
}
