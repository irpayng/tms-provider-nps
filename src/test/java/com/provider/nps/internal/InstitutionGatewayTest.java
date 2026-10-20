package com.provider.nps.internal;
import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.internal.grpc.AccountVerificationRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
class InstitutionGatewayTest {
    @Test
    void unconfiguredServiceFailsBeforeAttemptingAnyCall() {
        var gateway = new InstitutionGateway();
        var failure = assertThrows(StatusRuntimeException.class,
                () -> gateway.verify(AccountVerificationRequest.getDefaultInstance()));
        assertEquals(Status.Code.UNAVAILABLE, failure.getStatus().getCode());
    }
}
