package dev.espero.festival.account;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Which account a command or read refers to: a festival-wide TICKET or GOODS
 * account, or one booth's SPACE account identified by the booth's API id.
 */
public record OperationalAccountTarget(UUID festivalId, OperationalAccountPurpose purpose, String spaceId) {

    private static final Pattern SPACE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    public OperationalAccountTarget {
        if (festivalId == null) {
            throw new OperationalAccountException("ACCOUNT_FESTIVAL_ID_INVALID");
        }
        if (purpose == null) {
            throw new OperationalAccountException("ACCOUNT_PURPOSE_INVALID");
        }
        if (purpose == OperationalAccountPurpose.SPACE) {
            if (spaceId == null || !SPACE_ID.matcher(spaceId).matches()) {
                throw new OperationalAccountException("ACCOUNT_SPACE_ID_INVALID");
            }
        } else if (spaceId != null) {
            throw new OperationalAccountException("ACCOUNT_SPACE_ID_NOT_ALLOWED");
        }
    }

    public static OperationalAccountTarget festival(UUID festivalId, OperationalAccountPurpose purpose) {
        return new OperationalAccountTarget(festivalId, purpose, null);
    }

    public static OperationalAccountTarget space(UUID festivalId, String spaceId) {
        return new OperationalAccountTarget(festivalId, OperationalAccountPurpose.SPACE, spaceId);
    }

    /** The database scope column: the booth id for SPACE, empty otherwise. */
    public String scopeId() {
        return spaceId == null ? "" : spaceId;
    }
}
