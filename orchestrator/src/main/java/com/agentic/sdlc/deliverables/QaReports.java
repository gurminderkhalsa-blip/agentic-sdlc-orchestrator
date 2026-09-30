package com.agentic.sdlc.deliverables;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads the JUnit and JaCoCo XML reports that Gradle writes into a workspace's build/ folder. */
final class QaReports {

    record TestCase(String className, String name, String status, double seconds) {

        String simpleClass() {
            return className.substring(className.lastIndexOf('.') + 1);
        }

        /** The method name without JUnit's "()" or parameter suffix. */
        String method() {
            int paren = name.indexOf('(');
            int bracket = name.indexOf('[');
            int cut = paren >= 0 ? paren : bracket >= 0 ? bracket : name.length();
            return name.substring(0, cut).strip();
        }
    }

    record ClassCoverage(String className, int linesCovered, int linesMissed, int branchesCovered, int branchesMissed,
            String sourceFile) {

        double lineRatio() {
            return ratio(linesCovered, linesMissed);
        }

        double branchRatio() {
            return ratio(branchesCovered, branchesMissed);
        }
    }

    /** Uncovered (fully or partly) lines of one source file, as ranges like "12-14, 20". */
    record SourceGaps(String sourceFile, List<String> missedLineRanges, List<Integer> partlyCoveredBranchLines) {
    }

    record Coverage(int linesCovered, int linesMissed, int branchesCovered, int branchesMissed,
            List<ClassCoverage> classes, List<SourceGaps> gaps) {

        double lineRatio() {
            return ratio(linesCovered, linesMissed);
        }

        double branchRatio() {
            return ratio(branchesCovered, branchesMissed);
        }
    }

    private QaReports() {
    }

    static List<TestCase> junit(Path workspace) {
        Path dir = workspace.resolve("build/test-results/test");
        List<TestCase> cases = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return cases;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".xml")).sorted().toList()) {
                NodeList testcases = parse(file).getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    Element tc = (Element) testcases.item(i);
                    String status = has(tc, "failure") || has(tc, "error") ? "FAILED"
                            : has(tc, "skipped") ? "SKIPPED" : "PASSED";
                    cases.add(new TestCase(tc.getAttribute("classname"), tc.getAttribute("name"), status,
                            parseDouble(tc.getAttribute("time"))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }

    static Coverage jacoco(Path workspace) {
        Path report = workspace.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        if (!Files.exists(report)) {
            return null;
        }
        Element root = parse(report).getDocumentElement();
        int[] total = counters(root);
        List<ClassCoverage> classes = new ArrayList<>();
        List<SourceGaps> gaps = new ArrayList<>();
        for (Element pkg : children(root, "package")) {
            for (Element cls : children(pkg, "class")) {
                int[] c = counters(cls);
                classes.add(new ClassCoverage(cls.getAttribute("name").replace('/', '.'), c[0], c[1], c[2], c[3],
                        cls.getAttribute("sourcefilename")));
            }
            for (Element src : children(pkg, "sourcefile")) {
                List<Integer> missed = new ArrayList<>();
                List<Integer> partial = new ArrayList<>();
                for (Element line : children(src, "line")) {
                    int nr = Integer.parseInt(line.getAttribute("nr"));
                    int mi = Integer.parseInt(line.getAttribute("mi"));
                    int ci = Integer.parseInt(line.getAttribute("ci"));
                    int mb = Integer.parseInt(line.getAttribute("mb"));
                    if (mi > 0 && ci == 0) {
                        missed.add(nr);
                    } else if (mb > 0) {
                        partial.add(nr);
                    }
                }
                if (!missed.isEmpty() || !partial.isEmpty()) {
                    gaps.add(new SourceGaps(pkg.getAttribute("name").replace('/', '.') + "." + src.getAttribute("name"),
                            ranges(missed), partial));
                }
            }
        }
        return new Coverage(total[0], total[1], total[2], total[3], classes, gaps);
    }

    /** {LINE covered, LINE missed, BRANCH covered, BRANCH missed} from an element's direct counters. */
    private static int[] counters(Element element) {
        int[] result = new int[4];
        for (Element counter : children(element, "counter")) {
            int covered = Integer.parseInt(counter.getAttribute("covered"));
            int missed = Integer.parseInt(counter.getAttribute("missed"));
            if (counter.getAttribute("type").equals("LINE")) {
                result[0] = covered;
                result[1] = missed;
            } else if (counter.getAttribute("type").equals("BRANCH")) {
                result[2] = covered;
                result[3] = missed;
            }
        }
        return result;
    }

    static List<String> ranges(List<Integer> lines) {
        List<String> ranges = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            int start = lines.get(i);
            int end = start;
            while (i + 1 < lines.size() && lines.get(i + 1) == end + 1) {
                end = lines.get(++i);
            }
            ranges.add(start == end ? String.valueOf(start) : start + "-" + end);
            i++;
        }
        return ranges;
    }

    static double ratio(int covered, int missed) {
        return covered + missed == 0 ? 1.0 : (double) covered / (covered + missed);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element e && e.getTagName().equals(tag)) {
                result.add(e);
            }
        }
        return result;
    }

    private static boolean has(Element parent, String tag) {
        return parent.getElementsByTagName(tag).getLength() > 0;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Document parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
