package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.csv.CsvTable;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvTableTest {

    private static CsvTable parse(String s) { return CsvTable.parse(s.getBytes(StandardCharsets.UTF_8)); }

    private static String rejectedCode(String s) {
        return assertThrows(CsvTable.FileRejected.class, () -> parse(s)).code();
    }

    @Test
    void readsHeaderCaseInsensitivelyAndKeepsRowNumbers() {
        CsvTable t = parse("Ref,AMOUNT\nA,1.00\nB,2.00\n");
        assertEquals(java.util.List.of("ref", "amount"), t.headers());
        assertEquals(2, t.rows().size());
        assertEquals(1, t.rows().get(0).number());
        assertEquals("2.00", t.rows().get(1).values().get("amount"));
    }

    @Test
    void quotedCommaAndEmbeddedNewlineStayInsideOneField() {
        // The hand-rolled parser this replaces split on every comma and would read three columns here.
        CsvTable t = parse("ref,note\nA,\"Smith, John\"\nB,\"line one\nline two\"\n");
        assertEquals("Smith, John", t.rows().get(0).values().get("note"));
        assertEquals("line one\nline two", t.rows().get(1).values().get("note"));
        assertEquals(2, t.rows().size());
    }

    @Test
    void aByteOrderMarkIsNotPartOfTheFirstHeader() {
        CsvTable t = parse("﻿ref,amount\nA,1\n");
        assertEquals("ref", t.headers().get(0));
    }

    @Test
    void aRowWithTheWrongColumnCountIsKeptAsUnreadableNotShifted() {
        CsvTable t = parse("ref,amount,currency\nA,1.00,GBP\nB,2.00\nC,3.00,GBP,extra\n");
        assertNotNull(t.rows().get(0).values());
        assertNull(t.rows().get(1).values(), "short row must not be padded");
        assertNull(t.rows().get(2).values(), "long row must not be truncated");
    }

    @Test
    void blankLinesAreSkipped() {
        assertEquals(2, parse("ref\nA\n\n\nB\n").rows().size());
    }

    @Test
    void filesThatCannotBeTrustedAsAWholeAreRefused() {
        assertEquals("EMPTY_FILE", rejectedCode(""));
        assertEquals("EMPTY_FILE", rejectedCode("   \n  "));
        assertEquals("NO_ROWS", rejectedCode("ref,amount\n"));
        assertEquals("DUPLICATE_HEADER", rejectedCode("ref,Ref\nA,B\n"));
        assertEquals("UNREADABLE_CSV", rejectedCode("ref,note\nA,\"unterminated\n"));
    }

    @Test
    void aCellThatLooksLikeAFormulaIsStoredAsTextAndNothingElse() {
        // Spreadsheet formula injection matters when a value is later written to a sheet. Here it is
        // data: read verbatim, never evaluated, and exported only inside JSON strings.
        CsvTable t = parse("ref,note\n=HYPERLINK(\"http://x\"),@SUM(A1)\n");
        assertEquals("=HYPERLINK(\"http://x\")", t.rows().get(0).values().get("ref"));
        assertEquals("@SUM(A1)", t.rows().get(0).values().get("note"));
    }
}
