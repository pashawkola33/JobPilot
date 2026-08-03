package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Structural guards for the Phase 3.2.6 hotspot.
 *
 * <p>The regression these protect against was not "slightly slow": location screening
 * re-normalized the full job description once per {@code word(...)} call, and
 * {@code countryRestriction} issued seven such calls for each of ~254 countries. The
 * assertions below are deterministic operation counts, not timings, so they fail on the
 * shape of the work rather than on machine speed. One generous timeout is included purely
 * to catch accidental exponential behaviour. No network, database, or ATS access.
 */
class LocationEligibilityPerformanceTest {

    /** Counts normalize(...) calls and the characters each one processes. */
    private static final class CountingNormalizer implements LocationTextNormalizer {
        private final LocationTextNormalizer delegate = LocationTextNormalizer.standard();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong characters = new AtomicLong();
        private final AtomicInteger largeTextCalls = new AtomicInteger();
        private volatile int largeThreshold = Integer.MAX_VALUE;

        @Override
        public String normalize(String value) {
            calls.incrementAndGet();
            int length = value == null ? 0 : value.length();
            characters.addAndGet(length);
            if (length >= largeThreshold) largeTextCalls.incrementAndGet();
            return delegate.normalize(value);
        }

        private AtomicInteger calls() {
            return calls;
        }

        private AtomicLong characters() {
            return characters;
        }

        private AtomicInteger largeTextCalls() {
            return largeTextCalls;
        }

        private void reset(int threshold) {
            calls.set(0);
            characters.set(0);
            largeTextCalls.set(0);
            largeThreshold = threshold;
        }
    }

    @Test
    void aLargeDescriptionIsNormalizedABoundedNumberOfTimesPerVacancy() {
        CountingNormalizer normalizer = new CountingNormalizer();
        LocationEligibilityService service = new LocationEligibilityService(
                TestProperties.create().eligibility(), normalizer);
        String description = largeDescription(400);
        normalizer.reset(description.length());

        service.evaluate(vacancy("Remote", description, List.of()));

        // Before Phase 3.2.6 this was ~254 countries x 7 phrases x 2 calls, i.e. thousands
        // of full-description normalizations. The bound below is deliberately generous but
        // still orders of magnitude below the old behaviour.
        assertThat(normalizer.largeTextCalls().get())
                .as("normalizations of the full-size description text")
                .isLessThanOrEqualTo(8);
        assertThat(normalizer.calls().get())
                .as("total normalize(...) calls for one vacancy")
                .isLessThanOrEqualTo(64);
    }

    @Test
    void totalNormalizedCharactersScaleWithTextSizeNotWithVocabularySize() {
        CountingNormalizer normalizer = new CountingNormalizer();
        LocationEligibilityService service = new LocationEligibilityService(
                TestProperties.create().eligibility(), normalizer);
        String description = largeDescription(400);
        RawJob vacancy = vacancy("Remote", description, List.of());
        long inputSize = description.length();

        normalizer.reset(Integer.MAX_VALUE);
        service.evaluate(vacancy);

        // The old implementation processed roughly inputSize x 254 x 7 characters.
        // A small constant multiple proves the vocabulary loop no longer re-normalizes.
        assertThat(normalizer.characters().get())
                .as("characters normalized relative to input size")
                .isLessThanOrEqualTo(inputSize * 12);
    }

    @Test
    void doublingTheDescriptionRoughlyDoublesNormalizedCharacters() {
        CountingNormalizer normalizer = new CountingNormalizer();
        LocationEligibilityService service = new LocationEligibilityService(
                TestProperties.create().eligibility(), normalizer);

        normalizer.reset(Integer.MAX_VALUE);
        service.evaluate(vacancy("Remote", largeDescription(200), List.of()));
        long single = normalizer.characters().get();

        normalizer.reset(Integer.MAX_VALUE);
        service.evaluate(vacancy("Remote", largeDescription(400), List.of()));
        long doubled = normalizer.characters().get();

        // Linear, not multiplied by the number of restriction terms.
        assertThat(doubled).isLessThanOrEqualTo(single * 3);
    }

    @Test
    void everyReusablePatternIsCompiledOnceAndSharedAcrossEvaluations() {
        LocationTextNormalizer first = LocationTextNormalizer.standard();
        LocationTextNormalizer second = LocationTextNormalizer.standard();

        assertThat(first).isSameAs(second);
        // Repeated normalization is stable and allocation-free at the pattern level.
        assertThat(first.normalize("  Remote — EUROPE, România  "))
                .isEqualTo(second.normalize("  Remote — EUROPE, România  "))
                .isEqualTo("remote europe romania");
    }

    @Test
    void aLargeMixedBatchIsScreenedDeterministicallyWithinAGenerousBudget() {
        LocationEligibilityService service =
                new LocationEligibilityService(TestProperties.create());
        List<RawJob> batch = batch(300);

        long startedAt = System.nanoTime();
        List<LocationEligibilityDecision> decisions = new ArrayList<>(batch.size());
        for (RawJob vacancy : batch) decisions.add(service.evaluate(vacancy));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        Map<ScreeningDisposition, Integer> counts = new EnumMap<>(ScreeningDisposition.class);
        for (LocationEligibilityDecision decision : decisions) {
            counts.merge(decision.disposition(), 1, Integer::sum);
        }

        // Deterministic composition: 300 vacancies, one third of each disposition.
        assertThat(decisions).hasSize(300);
        assertThat(counts.get(ScreeningDisposition.MATCH)).isEqualTo(100);
        assertThat(counts.get(ScreeningDisposition.REJECT)).isEqualTo(100);
        assertThat(counts.get(ScreeningDisposition.REVIEW)).isEqualTo(100);
        // Result ordering is stable across runs.
        assertThat(decisions.get(0).disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decisions.get(1).disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(decisions.get(2).disposition()).isEqualTo(ScreeningDisposition.REVIEW);

        // Generous: the pre-refactor implementation needed minutes for this batch.
        assertThat(elapsed).as("300 large vacancies").isLessThan(Duration.ofSeconds(60));
    }

    /** 300 vacancies with large descriptions, cycling MATCH / REJECT / REVIEW. */
    private List<RawJob> batch(int size) {
        List<RawJob> vacancies = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            String filler = largeDescription(120);
            vacancies.add(switch (index % 3) {
                case 0 -> vacancy("Bucharest, Romania", "Java role. " + filler, List.of());
                case 1 -> vacancy("Remote", "Fully remote. US only. " + filler, List.of());
                default -> vacancy("Remote", "Java role. " + filler, List.of());
            });
        }
        return vacancies;
    }

    /**
     * Synthetic description that repeats country and restriction vocabulary, which is
     * exactly the shape that made the old nested loops expensive. Roughly 100 KiB at
     * {@code paragraphs = 400} — large enough to expose repeated work, small enough to
     * stay well inside heap.
     */
    private static String largeDescription(int paragraphs) {
        StringBuilder text = new StringBuilder(paragraphs * 260);
        for (int index = 0; index < paragraphs; index++) {
            text.append("We are a distributed engineering team collaborating across Germany, ")
                    .append("France, Spain, Italy, Poland, Netherlands, Sweden, Norway, Japan, ")
                    .append("Brazil, India, Mexico, Canada and Australia. Paragraph ")
                    .append(index)
                    .append(" describes tooling, testing, mentoring and delivery practices ")
                    .append("without stating any hiring restriction whatsoever. ");
        }
        return text.toString();
    }

    private RawJob vacancy(String location, String description, List<String> applicantLocations) {
        return new RawJob("fixture", "perf-" + Math.abs(description.hashCode()),
                "https://example.com/jobs/perf", "Java Developer", "Example", location,
                description, "Full-time", null, null, "fixture",
                new RawLocationData(null, List.of(location), List.of(), null,
                        applicantLocations, null, null));
    }
}
