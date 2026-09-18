package dev.espero.festival.account;

/** Local, short-lived file input for the account CLI; never render this record directly. */
record OperationalAccountInputDocument(
    String bankName,
    String accountNumber,
    String accountHolder,
    String transferLinkUrl,
    String bankId,
    Boolean tossLinkEnabled
) {

    OperationalAccountChange asChange() {
        return new OperationalAccountChange(
            OperationalAccountState.CONFIGURED,
            bankName,
            accountNumber,
            accountHolder,
            transferLinkUrl,
            bankId,
            Boolean.TRUE.equals(tossLinkEnabled)
        );
    }

    @Override
    public String toString() {
        return "OperationalAccountInputDocument[values=REDACTED]";
    }
}
