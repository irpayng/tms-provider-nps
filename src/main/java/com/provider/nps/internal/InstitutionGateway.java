package com.provider.nps.internal;

import com.provider.nps.internal.grpc.AccountVerificationRequest;
import com.provider.nps.internal.grpc.AccountVerificationResult;
import com.provider.nps.internal.grpc.InboundCreditDecision;
import com.provider.nps.internal.grpc.InboundCreditRequest;
import com.provider.nps.internal.grpc.InstitutionNpsServiceGrpc;
import com.provider.nps.internal.grpc.NotificationReceipt;
import com.provider.nps.internal.grpc.PaymentStatusNotification;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deadline-bound internal RPCs. Business services own durable idempotency and
 * decisions.
 */
@ApplicationScoped
public class InstitutionGateway {
    @GrpcClient("institution")
    InstitutionNpsServiceGrpc.InstitutionNpsServiceBlockingStub client;
    @ConfigProperty(name = "nps.internal.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "nps.internal.timeout-ms", defaultValue = "3000")
    long timeoutMs = 3000;
    @ConfigProperty(name = "nps.internal.bearer-token", defaultValue = "")
    java.util.Optional<String> bearerToken;

    private InstitutionNpsServiceGrpc.InstitutionNpsServiceBlockingStub call() {
        if (!enabled || timeoutMs <= 0)
            throw Status.UNAVAILABLE.withDescription("Internal NPS service is not configured").asRuntimeException();
        var stub = client.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
        if (bearerToken != null && bearerToken.filter(value -> !value.isBlank()).isPresent()) {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + bearerToken.get());
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        return stub;
    }
    public AccountVerificationResult verify(AccountVerificationRequest request) {
        return call().verifyAccount(request);
    }
    public InboundCreditDecision credit(InboundCreditRequest request) {
        return call().processInboundCredit(request);
    }
    public NotificationReceipt notifyStatus(PaymentStatusNotification request) {
        return call().notifyPaymentStatus(request);
    }
}
