package com.chasmet.fondvertstudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VersionComparatorTest {
    @Test
    public void comparesNumericVersions() {
        assertTrue(VersionComparator.compare("1.13.0", "1.12.8") > 0);
        assertTrue(VersionComparator.compare("2.0", "1.99.99") > 0);
        assertTrue(VersionComparator.compare("1.12.7", "1.12.8") < 0);
    }

    @Test
    public void acceptsTagPrefixAndMissingParts() {
        assertEquals(0, VersionComparator.compare("v1.13", "1.13.0"));
        assertEquals(0, VersionComparator.compare(" V1.13.0 ", "1.13"));
    }

    @Test
    public void handlesNullAndLargeComponents() {
        assertEquals(0, VersionComparator.compare(null, "0"));
        assertTrue(VersionComparator.compare("1.999999999999999999", "1.1") < 0);
    }
}
