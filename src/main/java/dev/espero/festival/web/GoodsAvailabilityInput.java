package dev.espero.festival.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Administrator input for a single goods combination's sale status. */
public final class GoodsAvailabilityInput {

    private String status;
    private boolean unexpectedFields;

    public GoodsAvailabilityInput() {}

    public String status() {
        return status;
    }

    @JsonProperty("status")
    public void setStatus(String status) {
        this.status = status;
    }

    @JsonAnySetter
    public void rejectUnexpectedField(String name, Object value) {
        unexpectedFields = true;
    }

    public boolean hasUnexpectedFields() {
        return unexpectedFields;
    }
}
