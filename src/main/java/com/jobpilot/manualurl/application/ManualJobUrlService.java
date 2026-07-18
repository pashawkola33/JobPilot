package com.jobpilot.manualurl.application;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.service.JobProcessingResult;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import com.jobpilot.manualurl.fetch.ManualAtsResolver;
import com.jobpilot.manualurl.fetch.ManualFetchException;
import com.jobpilot.manualurl.fetch.ManualUrlPolicy;
import com.jobpilot.manualurl.fetch.ManualUrlValidationException;
import com.jobpilot.manualurl.fetch.SafeManualPageFetcher;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.DeterministicManualJobParser;
import com.jobpilot.manualurl.parse.ManualParseResult;
import com.jobpilot.manualurl.parse.ManualParseStatus;
import java.util.ArrayList;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ManualJobUrlService {
    private static final Logger log = LoggerFactory.getLogger(ManualJobUrlService.class);

    private final ManualUrlPolicy urlPolicy;
    private final ManualAtsResolver atsResolver;
    private final SafeManualPageFetcher pageFetcher;
    private final DeterministicManualJobParser parser;
    private final ManualJobPersistenceService persistence;
    private final JobPilotProperties.ManualUrl settings;

    public ManualJobUrlService(ManualUrlPolicy urlPolicy, ManualAtsResolver atsResolver,
                               SafeManualPageFetcher pageFetcher, DeterministicManualJobParser parser,
                               ManualJobPersistenceService persistence, JobPilotProperties properties) {
        this.urlPolicy = urlPolicy;
        this.atsResolver = atsResolver;
        this.pageFetcher = pageFetcher;
        this.parser = parser;
        this.persistence = persistence;
        this.settings = properties.manualUrl();
    }

    public ManualJobSubmissionResult submit(String rawUrl) {
        ValidatedManualUrl validated;
        try {
            validated = urlPolicy.validate(rawUrl);
        } catch (ManualUrlValidationException exception) {
            return validationFailure(exception);
        }

        RawJob rawJob;
        try {
            Optional<RawJob> atsJob = atsResolver.fetch(validated.uri());
            if (atsJob.isPresent()) {
                rawJob = atsJob.get();
            } else {
                var fetched = pageFetcher.fetch(validated);
                ManualParseResult parsed = parser.parse(fetched);
                if (parsed.status() != ManualParseStatus.SUCCESS) {
                    return parseFailure(parsed.status());
                }
                rawJob = parsed.vacancy().toRawJob();
            }
        } catch (ManualFetchException exception) {
            return fetchFailure(exception);
        } catch (RuntimeException exception) {
            log.warn("Manual vacancy retrieval failed: category={}", exception.getClass().getSimpleName());
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.FETCH_FAILED, "The vacancy could not be retrieved safely.");
        }

        if (!validRawJob(rawJob)) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.PARSE_FAILED, "The vacancy did not contain the required fields.");
        }
        try {
            return success(persistence.persist(rawJob));
        } catch (RuntimeException exception) {
            log.warn("Manual vacancy persistence failed: category={}", exception.getClass().getSimpleName());
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.FETCH_FAILED, "The vacancy could not be processed.");
        }
    }

    private ManualJobSubmissionResult success(JobProcessingResult processed) {
        var score = processed.score();
        var risks = new ArrayList<String>();
        if (score != null) {
            risks.addAll(score.risks());
            risks.addAll(score.hardBlockers());
        }
        return new ManualJobSubmissionResult(
                processed.newlyCreated() ? ManualJobStatus.CREATED : ManualJobStatus.ALREADY_EXISTS,
                processed.job().getId(), processed.job().getCanonicalUrl(),
                score == null ? null : score.score(), score == null ? null : score.strengths(),
                risks, processed.newlyCreated() ? "Vacancy created." : "Vacancy already exists.");
    }

    private ManualJobSubmissionResult validationFailure(ManualUrlValidationException exception) {
        if (exception.getReason() == ManualUrlValidationException.Reason.RESOLUTION_FAILED) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.FETCH_FAILED, "The vacancy destination could not be resolved.");
        }
        return ManualJobSubmissionResult.failure(
                ManualJobStatus.INVALID_URL, "The URL is invalid or targets a prohibited destination.");
    }

    private ManualJobSubmissionResult fetchFailure(ManualFetchException exception) {
        if (exception.getCategory() == ManualFetchException.Category.BLOCKED_OR_PROTECTED) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.BLOCKED_OR_PROTECTED,
                    "The vacancy page requires protected or interactive access.");
        }
        if (exception.getCategory() == ManualFetchException.Category.UNSUPPORTED_CONTENT_TYPE) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.UNSUPPORTED_SOURCE, "The vacancy response type is unsupported.");
        }
        return ManualJobSubmissionResult.failure(
                ManualJobStatus.FETCH_FAILED, "The vacancy could not be retrieved safely.");
    }

    private ManualJobSubmissionResult parseFailure(ManualParseStatus status) {
        if (status == ManualParseStatus.BLOCKED_OR_PROTECTED) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.BLOCKED_OR_PROTECTED,
                    "The vacancy page requires protected or interactive access.");
        }
        if (status == ManualParseStatus.UNSUPPORTED_SOURCE) {
            return ManualJobSubmissionResult.failure(
                    ManualJobStatus.UNSUPPORTED_SOURCE,
                    "The public page could not be identified confidently as a vacancy.");
        }
        return ManualJobSubmissionResult.failure(
                ManualJobStatus.PARSE_FAILED, "The vacancy did not contain the required fields.");
    }

    private boolean validRawJob(RawJob job) {
        return job != null && !blank(job.url()) && !blank(job.title()) && !blank(job.company())
                && !blank(job.description()) && job.title().length() <= settings.maxTitleLength()
                && job.company().length() <= 300 && job.description().length() >= 40
                && job.description().length() <= settings.maxDescriptionLength();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
