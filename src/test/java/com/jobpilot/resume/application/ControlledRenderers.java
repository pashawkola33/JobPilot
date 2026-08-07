package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import com.jobpilot.resume.render.CoverNoteDocxRenderer;
import com.jobpilot.resume.render.ResumeDocxRenderer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Renderers a test can pause, so concurrency coverage depends on latches rather than on how
 * fast the host renders a DOCX. Both delegate to the real renderer and only block once armed,
 * which keeps every other test in a shared context on the genuine output.
 */
final class ControlledRenderers {
    private ControlledRenderers() {
    }

    /** Shared latch plumbing: arm with {@link #block}, disarm with {@link #resetControl}. */
    abstract static class Control {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        /** Blocks the next render until {@code release} opens, signalling {@code entered} first. */
        void block(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        void resetControl() {
            calls.set(0);
            entered = null;
            release = null;
        }

        int calls() {
            return calls.get();
        }

        /** Called by the subclass before delegating to the real renderer. */
        protected void awaitRelease() {
            calls.incrementAndGet();
            CountDownLatch currentEntered = entered;
            CountDownLatch currentRelease = release;
            if (currentEntered == null || currentRelease == null) return;
            currentEntered.countDown();
            try {
                if (!currentRelease.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out while controlling document rendering");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    static final class ControlledResumeDocxRenderer extends ResumeDocxRenderer {
        private final Control control = new Control() { };

        @Override
        public byte[] render(ResumeDocumentModel model) {
            control.awaitRelease();
            return super.render(model);
        }

        void block(CountDownLatch entered, CountDownLatch release) {
            control.block(entered, release);
        }

        void resetControl() {
            control.resetControl();
        }

        int calls() {
            return control.calls();
        }
    }

    static final class ControlledCoverNoteDocxRenderer extends CoverNoteDocxRenderer {
        private final Control control = new Control() { };

        @Override
        public byte[] render(CoverNoteDocumentModel model) {
            control.awaitRelease();
            return super.render(model);
        }

        void block(CountDownLatch entered, CountDownLatch release) {
            control.block(entered, release);
        }

        void resetControl() {
            control.resetControl();
        }

        int calls() {
            return control.calls();
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        ControlledResumeDocxRenderer controlledResumeDocxRenderer() {
            return new ControlledResumeDocxRenderer();
        }

        @Bean
        @Primary
        ControlledCoverNoteDocxRenderer controlledCoverNoteDocxRenderer() {
            return new ControlledCoverNoteDocxRenderer();
        }
    }
}
