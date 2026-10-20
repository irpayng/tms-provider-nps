package com.provider.nps.internal;

import com.provider.nps.internal.grpc.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class InternalServiceTestResource implements QuarkusTestResourceLifecycleManager {
    static volatile InboundCreditRequest credit;
    static volatile AccountVerificationRequest verification;
    static volatile PaymentStatusNotification notification;
    static volatile CreditDecision decision = CreditDecision.CREDIT_AUTHORIZED;
    static volatile boolean unavailable, hang, mismatch, accepted = true, verified = true;
    static final java.util.concurrent.atomic.AtomicInteger creditCalls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger verifyCalls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger statusCalls = new java.util.concurrent.atomic.AtomicInteger();
    private Server server;
    static void reset() {
        creditCalls.set(0);
        verifyCalls.set(0);
        statusCalls.set(0);
        credit = null;
        verification = null;
        notification = null;
        decision = CreditDecision.CREDIT_AUTHORIZED;
        unavailable = false;
        hang = false;
        mismatch = false;
        accepted = true;
        verified = true;
    }
    public Map<String, String> start() {
        try {
            server = ServerBuilder.forPort(0).addService(new InstitutionNpsServiceGrpc.InstitutionNpsServiceImplBase() {
                public void verifyAccount(AccountVerificationRequest request,
                        StreamObserver<AccountVerificationResult> observer) {
                    verifyCalls.incrementAndGet();
                    verification = request;
                    if (fail(observer))
                        return;
                    var result = AccountVerificationResult.newBuilder()
                            .setOriginalMessageId(mismatch ? "wrong-id" : request.getMessageId()).setVerified(verified)
                            .setReasonCode(verified ? "" : "33").setReasonDescription("Account & name mismatch")
                            .setCreditor(AccountIdentity.newBuilder().setAccountNumber(request.getAccountNumber())
                                    .setAccountName("Receiver & Co").setAccountDesignation("2").setAccountTier("3")
                                    .setIdType("BVN").setIdValue("22123456789"));
                    observer.onNext(result.build());
                    observer.onCompleted();
                }
                public void processInboundCredit(InboundCreditRequest request,
                        StreamObserver<InboundCreditDecision> observer) {
                    creditCalls.incrementAndGet();
                    credit = request;
                    if (fail(observer))
                        return;
                    observer.onNext(InboundCreditDecision.newBuilder()
                            .setOriginalMessageId(mismatch ? "wrong-id" : request.getMessageId()).setDecision(decision)
                            .setReasonCode("AC01").setReasonDescription("Account <closed>").build());
                    observer.onCompleted();
                }
                public void notifyPaymentStatus(PaymentStatusNotification request,
                        StreamObserver<NotificationReceipt> observer) {
                    statusCalls.incrementAndGet();
                    notification = request;
                    if (fail(observer))
                        return;
                    observer.onNext(NotificationReceipt.newBuilder()
                            .setMessageId(mismatch ? "wrong-id" : request.getMessageId()).setDurablyAccepted(accepted)
                            .build());
                    observer.onCompleted();
                }
                private boolean fail(StreamObserver<?> observer) {
                    if (hang)
                        return true;
                    if (unavailable) {
                        observer.onError(Status.UNAVAILABLE.asRuntimeException());
                        return true;
                    }
                    return false;
                }
            }).build().start();
            return Map.of("quarkus.grpc.clients.institution.test-port", Integer.toString(server.getPort()),
                    "quarkus.grpc.clients.institution.port", Integer.toString(server.getPort()),
                    "quarkus.grpc.clients.institution.host", "localhost", "quarkus.grpc.clients.institution.plain-text",
                    "true");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    public void stop() {
        if (server != null)
            server.shutdownNow();
    }
}
