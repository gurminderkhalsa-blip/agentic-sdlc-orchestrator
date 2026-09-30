package com.agentic.sdlc.deliverables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QaReportsTest {

    @Test
    void uncoveredLinesAreGroupedIntoRanges() {
        assertThat(QaReports.ranges(List.of(3, 4, 5, 9, 12, 13))).containsExactly("3-5", "9", "12-13");
        assertThat(QaReports.ranges(List.of())).isEmpty();
    }

    @Test
    void testMethodNamesAreNormalised() {
        assertThat(new QaReports.TestCase("a.b.LinkTest", "rejectsBadUrl(String)[2]", "PASSED", 0).method())
                .isEqualTo("rejectsBadUrl");
        assertThat(new QaReports.TestCase("a.b.LinkTest", "createsLink()", "PASSED", 0).simpleClass())
                .isEqualTo("LinkTest");
    }

    @Test
    void markdownCellsEscapePipesAndNewlines() {
        assertThat(Md.cell("a|b\nc")).isEqualTo("a\\|b<br>c");
    }
}
