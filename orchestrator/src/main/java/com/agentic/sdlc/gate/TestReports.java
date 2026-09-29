package com.agentic.sdlc.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Parses JUnit XML results and the JaCoCo XML report that Gradle writes into build/. */
final class TestReports {

    record TestSummary(int tests, int failures, int errors, int skipped, List<String> failing) {
    }

    private TestReports() {
    }

    static TestSummary junit(Path workspace) {
        Path dir = workspace.resolve("build/test-results/test");
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        List<String> failing = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new TestSummary(0, 0, 0, 0, failing);
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".xml")).toList()) {
                Element suite = parse(file).getDocumentElement();
                tests += intAttr(suite, "tests");
                failures += intAttr(suite, "failures");
                errors += intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
                NodeList cases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element testCase = (Element) cases.item(i);
                    Element problem = first(testCase, "failure");
                    if (problem == null) {
                        problem = first(testCase, "error");
                    }
                    if (problem != null && failing.size() < 15) {
                        String detail = problem.getTextContent().lines().limit(6)
                                .map(line -> line.length() > 300 ? line.substring(0, 300) + " ..." : line)
                                .reduce((a, b) -> a + "\n    " + b).orElse("");
                        failing.add(testCase.getAttribute("classname") + "." + testCase.getAttribute("name")
                                + ": " + detail);
                    }
                }
            }
        } catch (IOException e) {
            failing.add("could not read test results: " + e.getMessage());
        }
        return new TestSummary(tests, failures, errors, skipped, failing);
    }

    /** Line coverage ratio from the report-level LINE counter, or -1 if there is no report. */
    static double lineCoverage(Path workspace) {
        Path report = workspace.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        if (!Files.exists(report)) {
            return -1;
        }
        Element root = parse(report).getDocumentElement();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element counter && counter.getTagName().equals("counter")
                    && counter.getAttribute("type").equals("LINE")) {
                double missed = Double.parseDouble(counter.getAttribute("missed"));
                double covered = Double.parseDouble(counter.getAttribute("covered"));
                return covered + missed == 0 ? 0 : covered / (covered + missed);
            }
        }
        return 0;
    }

    private static Document parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static Element first(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static int intAttr(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }
}
