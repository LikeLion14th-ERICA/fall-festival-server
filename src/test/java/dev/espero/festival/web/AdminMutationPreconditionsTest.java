package dev.espero.festival.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminMutationPreconditionsTest {

    private static final String CURRENT = "\"d2d2ce4f5e433c4461ec334f345c3fd680a04bf90d77bb7c36ce2f50be6d69a1\"";
    private final AdminMutationPreconditions preconditions = new AdminMutationPreconditions();

    @Test
    void requiresIfMatchByDefault() {
        ApiException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> preconditions.requireCurrentRepresentation(request(null), AdminMutationConcurrency.IF_MATCH_REQUIRED, CURRENT),
            ApiException.class
        );

        assertThat(exception.status().value()).isEqualTo(428);
        assertThat(exception.code()).isEqualTo("PRECONDITION_REQUIRED");
    }

    @Test
    void rejectsMalformedAndStaleEtagsThenAcceptsTheCurrentStrongTag() {
        assertApiError(request("W/" + CURRENT), HttpStatusFixture.BAD_REQUEST, "INVALID_IF_MATCH");
        assertApiError(request("\"" + "0".repeat(64) + "\""), HttpStatusFixture.CONFLICT, "EDIT_CONFLICT");
        assertThatCode(() -> preconditions.requireCurrentRepresentation(
            request(CURRENT), AdminMutationConcurrency.IF_MATCH_REQUIRED, CURRENT
        )).doesNotThrowAnyException();
    }

    @Test
    void extractsOneTrimmedStrongIfMatchAndRejectsDuplicateHeaders() {
        MockHttpServletRequest valid = request("  " + CURRENT + "  ");
        assertThat(preconditions.requireValidIfMatch(valid)).isEqualTo(CURRENT);

        MockHttpServletRequest duplicate = request(CURRENT);
        duplicate.addHeader("If-Match", CURRENT);
        assertThatThrownBy(() -> preconditions.requireValidIfMatch(duplicate))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("INVALID_IF_MATCH"));
    }

    @Test
    void lastWriteWinsIsAnExplicitExceptionToTheDefaultPolicy() throws Exception {
        Method protectedMethod = PolicyProbe.class.getDeclaredMethod("protectedMutation");
        Method lastWriteWinsMethod = PolicyProbe.class.getDeclaredMethod("lastWriteWinsMutation");

        assertThat(protectedMethod.getAnnotation(AdminMutationPolicy.class).value())
            .isEqualTo(AdminMutationConcurrency.IF_MATCH_REQUIRED);
        assertThat(lastWriteWinsMethod.getAnnotation(AdminMutationPolicy.class).value())
            .isEqualTo(AdminMutationConcurrency.LAST_WRITE_WINS);
        assertThatCode(() -> preconditions.requireCurrentRepresentation(
            request("invalid"), AdminMutationConcurrency.LAST_WRITE_WINS, CURRENT
        )).doesNotThrowAnyException();
    }

    private void assertApiError(MockHttpServletRequest request, int status, String code) {
        assertThatThrownBy(() -> preconditions.requireCurrentRepresentation(
            request, AdminMutationConcurrency.IF_MATCH_REQUIRED, CURRENT
        )).isInstanceOf(ApiException.class).satisfies(exception -> {
            ApiException api = (ApiException) exception;
            assertThat(api.status().value()).isEqualTo(status);
            assertThat(api.code()).isEqualTo(code);
        });
    }

    private MockHttpServletRequest request(String ifMatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v2/admin/crowding");
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        return request;
    }

    private static final class HttpStatusFixture {
        private static final int BAD_REQUEST = 400;
        private static final int CONFLICT = 409;
    }

    private static final class PolicyProbe {
        @AdminMutationPolicy
        void protectedMutation() {}

        @AdminMutationPolicy(AdminMutationConcurrency.LAST_WRITE_WINS)
        void lastWriteWinsMutation() {}
    }
}
