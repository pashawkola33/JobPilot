package com.jobpilot.manualurl.fetch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.support.TestProperties;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class JdkManualHttpTransportTest {
    @Test
    void appliesResponseDeadlineWhileStreamingTheBody() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        InputStream blockingBody = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    closed.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Interrupted", interrupted);
                }
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        JdkManualHttpTransport transport = new JdkManualHttpTransport(TestProperties.create());

        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);

            assertThatThrownBy(() -> transport.readBody(blockingBody, 1_024, deadline))
                    .isInstanceOf(ManualFetchException.class)
                    .extracting(error -> ((ManualFetchException) error).getCategory())
                    .isEqualTo(ManualFetchException.Category.TIMEOUT);
        } finally {
            transport.close();
        }
    }
}
