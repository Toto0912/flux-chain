package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ForwardChainSelectionTest {
    @Test void usesBestPathWhenItBeatsCurrentByMoreThanThreshold() {
        assertEquals(1, ForwardChainSelection.choosePath(Arrays.asList(50d, 40d), Arrays.asList(true, true), 0, 5));
    }
    @Test void keepsCurrentPathWhenImprovementDoesNotExceedThreshold() {
        assertEquals(0, ForwardChainSelection.choosePath(Arrays.asList(50d, 45d), Arrays.asList(true, true), 0, 5));
    }
    @Test void immediatelyLeavesFailedCurrentPathWhenAnotherIsAvailable() {
        assertEquals(1, ForwardChainSelection.choosePath(Arrays.asList(null, 900d), Arrays.asList(false, true), 0, 1000));
    }
    @Test void keepsLastChoiceWhenEveryPathIsUnavailable() {
        assertEquals(1, ForwardChainSelection.choosePath(Arrays.asList(null, null), Arrays.asList(false, false), 1, 1));
    }
    @Test void countsCartesianRouteCombinationsWithoutA125PathLimit() {
        assertEquals(125, ForwardChainSelection.countCombinations(Arrays.asList(5, 5, 5)));
        assertEquals(1024, ForwardChainSelection.countCombinations(Arrays.asList(4, 4, 4, 4, 4)));
        assertEquals(0, ForwardChainSelection.countCombinations(Collections.singletonList(0)));
    }
}
