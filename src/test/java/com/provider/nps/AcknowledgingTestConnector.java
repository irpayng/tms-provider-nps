package com.provider.nps;

import io.smallrye.reactive.messaging.connector.OutboundConnector;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.Flow;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;

/**
 * Test-only sink for API tests. Broker failures are covered separately by
 * handler tests.
 */
@ApplicationScoped
@Connector("test-acknowledging")
public class AcknowledgingTestConnector implements OutboundConnector {
    @Override
    public Flow.Subscriber<? extends Message<?>> getSubscriber(Config config) {
        return new Flow.Subscriber<Message<?>>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Message<?> message) {
                message.ack();
            }

            @Override
            public void onError(Throwable failure) {
                throw new IllegalStateException("Test channel failed", failure);
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
