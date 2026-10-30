package com.provider.nps.client;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to start a deployed adapter with test-only settings. The crypto
 * bypass skips signature verification on NIBSS callbacks, so a deployed service
 * running with it would accept forged credits.
 *
 * Unlike the other providers this does not register with config: a
 * bank-transfer route here would send live transfers to an adapter that has no
 * NIBSS credentials or institution service yet.
 */
@ApplicationScoped
public class ProviderStartup {

    @ConfigProperty(name = "nps.skip-crypto", defaultValue = "false")
    boolean skipCrypto;

    @ConfigProperty(name = "nps.inbound.mode", defaultValue = "grpc")
    String inboundMode;

    @ConfigProperty(name = "nps.http.enabled", defaultValue = "false")
    boolean httpEnabled;

    void onStart(@Observes StartupEvent ev) {
        requireDeployableSettings(LaunchMode.current(), skipCrypto, inboundMode);
        if (LaunchMode.current() == LaunchMode.NORMAL && httpEnabled)
            Log.warn("NPS HTTP testing facade is enabled; it has no authentication");
    }

    static void requireDeployableSettings(LaunchMode mode, boolean skipCrypto, String inboundMode) {
        if (mode != LaunchMode.NORMAL)
            return;
        if (skipCrypto)
            throw new IllegalStateException(
                    "nps.skip-crypto is test-only: it disables signing, encryption and callback signature checks");
        if (!"grpc".equals(inboundMode))
            throw new IllegalStateException("nps.inbound.mode=" + inboundMode
                    + " is test-only; a deployed adapter forwards callbacks over gRPC");
    }
}
