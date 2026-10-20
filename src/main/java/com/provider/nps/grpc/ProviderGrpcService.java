package com.provider.nps.grpc;

import com.provider.nps.application.NpsOperations;
import com.provider.nps.dto.ProviderRequest;
import com.provider.nps.dto.ProviderResponse;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import java.math.BigDecimal;

/** Protobuf transport for the shared NPS application operations. */
@GrpcService
@Blocking
public class ProviderGrpcService extends ProviderServiceGrpc.ProviderServiceImplBase {
    private final NpsOperations operations;

    @Inject
    public ProviderGrpcService(NpsOperations operations) {
        this.operations = operations;
    }

    @Override
    public void execute(ProviderExecuteRequest request, StreamObserver<ProviderExecuteResponse> responseObserver) {
        ProviderResponse result = Double.isFinite(request.getAmount())
                ? operations.execute(new ProviderRequest(request.getAction(), request.getReference(),
                        BigDecimal.valueOf(request.getAmount()), request.getMetadataMap()))
                : ProviderResponse.failed("Invalid amount", request.getReference());
        ProviderExecuteResponse.Builder resp = ProviderExecuteResponse.newBuilder().setStatus(nullSafe(result.status))
                .setMessage(nullSafe(result.message));

        if (result.providerReference != null)
            resp.setProviderReference(result.providerReference);
        if (result.responseCode != null)
            resp.setResponseCode(result.responseCode);
        if (result.dataJson != null)
            resp.setDataJson(result.dataJson);
        if (result.requestXml != null)
            resp.setRequestIsoHex(result.requestXml);
        if (result.responseXml != null)
            resp.setResponseIsoHex(result.responseXml);

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
