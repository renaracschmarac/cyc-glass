package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CycClientTest {

    @Test
    public void displayAssistLevelMapsCycRawSteps() {
        assertEquals(0, CycClient.displayAssistLevel(0));
        assertEquals(1, CycClient.displayAssistLevel(3));
        assertEquals(2, CycClient.displayAssistLevel(6));
        assertEquals(3, CycClient.displayAssistLevel(9));
    }

    @Test
    public void displayAssistLevelPassesUnexpectedRawValuesThrough() {
        assertEquals(4, CycClient.displayAssistLevel(4));
    }
}
