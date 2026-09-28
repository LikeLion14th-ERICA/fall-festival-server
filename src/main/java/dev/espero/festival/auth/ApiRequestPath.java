package dev.espero.festival.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

/** Uses MVC's segment decoding and matrix-parameter handling for security policy selection. */
public final class ApiRequestPath {

    private ApiRequestPath() {}

    public static String of(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element : RequestPath.parse(request.getRequestURI(), request.getContextPath())
            .pathWithinApplication().elements()) {
            path.append(element instanceof PathContainer.PathSegment segment
                ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }
}
