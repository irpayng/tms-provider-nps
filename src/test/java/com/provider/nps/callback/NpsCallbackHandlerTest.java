package com.provider.nps.callback;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.callback.NpsCallbackResource.InboundCreditTransfer;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

class NpsCallbackHandlerTest {
    @SuppressWarnings("unchecked")
    private Emitter<String> emitter(Consumer<Message<?>> delivery) {
        return (Emitter<String>) Proxy.newProxyInstance(Emitter.class.getClassLoader(), new Class<?>[]{Emitter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("send")) {
                        Message<?> message = (Message<?>) args[0];
                        delivery.accept(message);
                    }
                    return null;
                });
    }

    private InboundCreditTransfer transfer(String amount) {
        var transfer = new InboundCreditTransfer();
        transfer.setMessageId("original-id");
        transfer.setCreditorAccount("0123456789");
        transfer.setAmount(amount);
        return transfer;
    }

    @Test
    void acknowledgesTransferOnlyAfterBrokerAcknowledges() {
        var handler = new NpsCallbackHandler();
        handler.inboundTransferEmitter = emitter(Message::ack);
        assertTrue(handler.handleInboundCreditTransfer(transfer("100.00")).isSuccess());
    }

    @Test
    void brokerFailurePropagatesInsteadOfAcceptingTransfer() {
        var handler = new NpsCallbackHandler();
        handler.inboundTransferEmitter = emitter(
                message -> message.nack(new IllegalStateException("Broker unavailable")));
        assertThrows(IllegalStateException.class, () -> handler.handleInboundCreditTransfer(transfer("100.00")));
    }

    @Test
    void missingAcknowledgementTimesOutInsteadOfAcceptingTransfer() {
        var handler = new NpsCallbackHandler();
        handler.publishTimeoutMs = 20;
        handler.inboundTransferEmitter = emitter(message -> {
        });
        assertThrows(IllegalStateException.class, () -> handler.handleInboundCreditTransfer(transfer("100.00")));
    }

    @Test
    void rejectsInvalidAmountsBeforePublishing() {
        var handler = new NpsCallbackHandler();
        for (String amount : new String[]{"0", "-1", "NaN", "invalid"}) {
            assertFalse(handler.handleInboundCreditTransfer(transfer(amount)).isSuccess());
        }
    }
    @Test
    void paymentEventsPreserveAuthorizationAndOriginalIdentifiers() throws Exception {
        var handler = new NpsCallbackHandler();
        var published = new java.util.concurrent.atomic.AtomicReference<String>();
        handler.statusUpdateEmitter = emitter(message -> {
            published.set((String) message.getPayload());
            message.ack();
        });
        var status = new com.provider.nps.message.NpsResponseParser.PaymentStatusResponse();
        status.setOriginalMessageId("original-id");
        status.setMessageId("response-id");
        status.setStatusId("NOAN");
        status.setTransactionStatus("RJCT");
        status.setReasonCode("AC01");
        status.setOriginalInstructionId("instruction-id");
        status.setTransactionId("transaction-id");
        status.setEndToEndId("end-id");
        status.setReasonDescription("Rejected by timeout");
        handler.handlePaymentStatusUpdate(status);
        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(published.get());
        assertEquals("NOAN", event.get("statusId").asText());
        assertEquals("original-id", event.get("originalMessageId").asText());
        assertEquals("instruction-id", event.get("originalInstructionId").asText());
        assertEquals("transaction-id", event.get("originalTransactionId").asText());
        assertEquals("Rejected by timeout", event.get("reasonDescription").asText());
    }
}
