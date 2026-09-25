package com.intwfs.mintwf.core;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class MintwfTest {

    @Test
    void versionIsNeverBlank() {
        assertFalse(Mintwf.version().isBlank());
    }
}
