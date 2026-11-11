package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BayesianEvidenceDaoMappingTest {

    @Test
    void listRecentByAssetSelectsOnlyDecisionColumns() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/BayesianEvidenceDao.xml"));
        String query = sectionOf(xml, "id=\"listRecentByAsset\"", "</select>");

        assertFalse(query.contains("SELECT *"), "listRecentByAsset 不得整行加载 evidence_json");
        assertTrue(query.contains("context_key"));
        assertTrue(query.contains("alpha, beta, posterior_mean, effective_sample_size"));
        assertTrue(query.contains(
                "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.failureCategory')), 'null') AS failure_category"));
    }

    private static String sectionOf(String xml, String startMarker, String endMarker) {
        int start = xml.indexOf(startMarker);
        assertTrue(start >= 0, "缺少 " + startMarker);
        int end = xml.indexOf(endMarker, start);
        assertTrue(end > start, "缺少 " + endMarker);
        return xml.substring(start, end);
    }
}
