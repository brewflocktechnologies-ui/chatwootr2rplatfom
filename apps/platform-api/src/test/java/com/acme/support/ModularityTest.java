package com.acme.support;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void moduleStructureIsValid() {
        ApplicationModules.of(PlatformApiApplication.class).verify();
    }
}
