package dev.espero.festival.account;

import dev.espero.festival.persistence.OperationalAccountStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
        return store.findCurrent(requiredFestival(festivalId), Objects.requireNonNull(purpose, "Purpose is required"));
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewSet(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountChange change,
        String suppliedLastFour
    ) {
        OperationalAccountChange normalized = normalize(change);
        requireLastFour(normalized, suppliedLastFour);
        return preview(OperationalAccountChangeAction.SET, festivalId, purpose, expectedVersion, normalized);
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
        OperationalAccountChange normalized = normalize(change);
        requireLastFour(normalized, suppliedLastFour);
        return write(OperationalAccountChangeAction.SET, festivalId, purpose, expectedVersion, normalized, audit);
    }

    @Transactional(readOnly = true)
    public OperationalAccountChangeResult previewClear(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion
    ) {
        return preview(
            OperationalAccountChangeAction.CLEAR,
            festivalId,
            purpose,
            expectedVersion,
            OperationalAccountChange.unconfigured()
        );
    }

    @Transactional
    public OperationalAccountChangeResult clear(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountAuditMetadata audit
    ) {
        return write(
            OperationalAccountChangeAction.CLEAR,
            festivalId,
            purpose,
            expectedVersion,
            OperationalAccountChange.unconfigured(),
            audit
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
        OperationalAccountChange restored = restoredChange(festivalId, purpose, sourceVersion);
        requireLastFour(restored, suppliedLastFour);
        return preview(OperationalAccountChangeAction.RESTORE, festivalId, purpose, expectedVersion, restored);
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
        OperationalAccountChange restored = restoredChange(festivalId, purpose, sourceVersion);
        requireLastFour(restored, suppliedLastFour);
        return write(OperationalAccountChangeAction.RESTORE, festivalId, purpose, expectedVersion, restored, audit);
    }

    private OperationalAccountChange restoredChange(
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long sourceVersion
    ) {
        if (sourceVersion < 1) {
            throw new OperationalAccountException("ACCOUNT_HISTORY_VERSION_INVALID");
        }
        OperationalAccountHistory history = store.findHistory(
            requiredFestival(festivalId), Objects.requireNonNull(purpose, "Purpose is required"), sourceVersion
        ).orElseThrow(() -> new OperationalAccountException("ACCOUNT_HISTORY_VERSION_NOT_FOUND"));
        return normalize(history.restoredChange());
    }

    private OperationalAccountChangeResult preview(
        OperationalAccountChangeAction action,
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountChange desired
    ) {
        Optional<OperationalAccountSetting> current = store.findCurrent(
            requiredFestival(festivalId), Objects.requireNonNull(purpose, "Purpose is required")
        );
        verifyExpectedVersion(current, expectedVersion);
        if (current.filter(setting -> sameValues(setting, desired)).isPresent()) {
            return new OperationalAccountChangeResult(action, current.orElseThrow(), current.orElseThrow(), false);
        }
        return new OperationalAccountChangeResult(
            action, current.orElse(null), nextSetting(festivalId, purpose, current, desired), true
        );
    }

    private OperationalAccountChangeResult write(
        OperationalAccountChangeAction action,
        UUID festivalId,
        OperationalAccountPurpose purpose,
        long expectedVersion,
        OperationalAccountChange desired,
        OperationalAccountAuditMetadata audit
    ) {
        UUID requiredFestival = requiredFestival(festivalId);
        OperationalAccountPurpose requiredPurpose = Objects.requireNonNull(purpose, "Purpose is required");
        Objects.requireNonNull(audit, "Audit metadata is required");
        Optional<OperationalAccountSetting> current = store.findCurrentForUpdate(requiredFestival, requiredPurpose);
        verifyExpectedVersion(current, expectedVersion);
        if (current.filter(setting -> sameValues(setting, desired)).isPresent()) {
            return new OperationalAccountChangeResult(action, current.orElseThrow(), current.orElseThrow(), false);
        }

        OperationalAccountSetting next = nextSetting(requiredFestival, requiredPurpose, current, desired);
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
        UUID festivalId,
        OperationalAccountPurpose purpose,
        Optional<OperationalAccountSetting> current,
        OperationalAccountChange desired
    ) {
        long nextVersion = current.map(OperationalAccountSetting::version)
            .map(version -> version + 1)
            .orElseGet(() -> store.nextVersion(festivalId, purpose));
        return new OperationalAccountSetting(
            festivalId,
            purpose,
            desired.state(),
            nextVersion,
            desired.bankName(),
            desired.accountNumber(),
            desired.accountHolder(),
            desired.transferLinkUrl(),
            clock.instant()
        );
    }

    private OperationalAccountChange normalize(OperationalAccountChange change) {
        Objects.requireNonNull(change, "Operational account change is required");
        if (change.state() == OperationalAccountState.UNCONFIGURED) {
            return OperationalAccountChange.unconfigured();
        }
        return new OperationalAccountChange(
            OperationalAccountState.CONFIGURED,
            requiredText(change.bankName(), "ACCOUNT_BANK_NAME_INVALID", 100),
            accountNumber(change.accountNumber()),
            requiredText(change.accountHolder(), "ACCOUNT_HOLDER_INVALID", 100),
            transferLinks.validateOptional(change.transferLinkUrl())
        );
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
            && Objects.equals(current.transferLinkUrl(), desired.transferLinkUrl());
    }

    private UUID requiredFestival(UUID festivalId) {
        if (festivalId == null) {
            throw new OperationalAccountException("ACCOUNT_FESTIVAL_ID_INVALID");
        }
        return festivalId;
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
                    "operational_account_changed purpose={} version={} occurred_at={} change_count=1",
                    setting.purpose(), setting.version(), setting.updatedAt()
                );
            }
        });
    }
}
