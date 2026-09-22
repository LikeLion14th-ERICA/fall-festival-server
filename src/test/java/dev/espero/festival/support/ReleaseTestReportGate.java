package dev.espero.festival.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Maven's release profile calls this after Surefire, including when tests were skipped. */
public final class ReleaseTestReportGate {
    private static final String MIGRATION_GATE = "Postgresql17MigrationReleaseTest";

    private ReleaseTestReportGate() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]), args[1]);
        System.out.println("PostgreSQL 17 release reports passed: all required classes ran; zero skipped tests.");
    }

    static void verify(Path directory, String selection) throws Exception {
        List<String> required = Arrays.stream(selection.split(",", -1)).map(String::trim).toList();
        if (required.stream().anyMatch(name -> !name.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))
            || required.stream().noneMatch(name -> simpleName(name).equals(MIGRATION_GATE))) {
            throw new IllegalStateException("Release selection must list full test classes including " + MIGRATION_GATE);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Missing release test reports");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        List<Element> suites;
        try (var files = Files.list(directory)) {
            suites = files.filter(path -> path.getFileName().toString().matches("TEST-.+\\.xml"))
                .sorted().map(path -> {
                    try {
                        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
                    } catch (Exception exception) {
                        throw new IllegalStateException("Invalid release test report: " + path.getFileName(), exception);
                    }
                }).toList();
        }
        for (String name : required) {
            List<Element> matches = suites.stream().filter(suite -> name.contains(".")
                ? suite.getAttribute("name").equals(name)
                : simpleName(suite.getAttribute("name")).equals(name)).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Missing or ambiguous required release test report: " + name);
            }
            Element suite = matches.getFirst();
            int minimumTests = simpleName(name).equals(MIGRATION_GATE) ? 2 : 1;
            if (Integer.parseInt(suite.getAttribute("tests")) < minimumTests
                || Integer.parseInt(suite.getAttribute("skipped")) != 0
                || Integer.parseInt(suite.getAttribute("failures")) != 0
                || Integer.parseInt(suite.getAttribute("errors")) != 0) {
                throw new IllegalStateException("Required release tests did not all pass without skips: " + name);
            }
        }
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
