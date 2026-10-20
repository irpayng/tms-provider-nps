package com.provider.nps.internal;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
public class InboundGrpcProfile implements QuarkusTestProfile {
    public Map<String, String> getConfigOverrides() {
        return Map.of("nps.inbound.mode", "grpc", "nps.internal.enabled", "true", "nps.internal.timeout-ms", "1000",
                "nps.source-id", "999057", "nps.skip-crypto", "false", "nps.nibss-public-key-path",
                "src/test/resources/test-public.pem");
    }
}
