package dev.espero.festival.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Only bounded, non-payload diagnostics may cross into the request log. */
public final class RequestDiagnostics {
    static final String ERROR = RequestDiagnostics.class.getName() + ".error";
    static final String FAILURE = RequestDiagnostics.class.getName() + ".failure";
    static final String META = RequestDiagnostics.class.getName() + ".meta";

    private RequestDiagnostics() {}

    public static void error(HttpServletRequest request, String code) {
        request.setAttribute(ERROR, code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "UNKNOWN");
    }

    public static void failure(HttpServletRequest request, Throwable failure) {
        request.setAttribute(FAILURE, describe(failure));
    }

    static String describe(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayList<String> causes = new ArrayList<>();
        for (Throwable current = failure; current != null && causes.size() < 5 && seen.add(current);
             current = current.getCause()) {
            String type = current.getClass().getName();
            String location = "-";
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("dev.espero.festival.")) {
                    location = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    break;
                }
            }
            causes.add(type + "@" + location);
        }
        // Never call Throwable.toString/getMessage or include SQL, filenames or suppressed exceptions.
        return String.join("|", causes);
    }
}
