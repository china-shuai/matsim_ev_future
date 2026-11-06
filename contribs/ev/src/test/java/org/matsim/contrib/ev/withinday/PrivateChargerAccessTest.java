package org.matsim.contrib.ev.withinday;

import org.junit.jupiter.api.Test;

/** Maven/JUnit entry point for the standalone ownership regression suite. */
public class PrivateChargerAccessTest {
    @Test
    public void publicSubstitutionAndPrivateOwnershipAreEnforced() throws Exception {
        PrivateChargerAccessRegression.main(new String[0]);
    }
}
