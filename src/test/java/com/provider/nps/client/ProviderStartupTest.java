package com.provider.nps.client;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

class ProviderStartupTest {
    @Test
    void deployedServiceRefusesTheCryptoBypass() {
        var failure = assertThrows(IllegalStateException.class,
                () -> ProviderStartup.requireDeployableSettings(LaunchMode.NORMAL, true, "grpc"));
        assertTrue(failure.getMessage().contains("nps.skip-crypto"));
    }

    @Test
    void deployedServiceRefusesTheLegacyInboundMode() {
        var failure = assertThrows(IllegalStateException.class,
                () -> ProviderStartup.requireDeployableSettings(LaunchMode.NORMAL, false, "legacy-test"));
        assertTrue(failure.getMessage().contains("legacy-test"));
    }

    @Test
    void deployedServiceStartsWithVerifiedCallbacks() {
        assertDoesNotThrow(() -> ProviderStartup.requireDeployableSettings(LaunchMode.NORMAL, false, "grpc"));
    }

    @Test
    void testAndDevelopmentModesKeepTheirFixtures() {
        assertDoesNotThrow(() -> ProviderStartup.requireDeployableSettings(LaunchMode.TEST, true, "legacy-test"));
        assertDoesNotThrow(
                () -> ProviderStartup.requireDeployableSettings(LaunchMode.DEVELOPMENT, true, "legacy-test"));
    }
}
