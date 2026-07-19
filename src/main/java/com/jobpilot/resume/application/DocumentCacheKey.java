package com.jobpilot.resume.application;

import com.jobpilot.common.Hashing;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentContactBlock;
import com.jobpilot.resume.domain.DocumentFormat;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DocumentCacheKey {
    private final DocumentProperties documents;
    private final JobPilotProperties.Llm llm;
    private final DocumentContactCacheIdentity contactIdentities;

    public DocumentCacheKey(DocumentProperties documents, JobPilotProperties properties,
                            DocumentContactCacheIdentity contactIdentities) {
        this.documents = documents;
        this.llm = properties.llm();
        this.contactIdentities = contactIdentities;
    }

    public String resume(JobDocumentFacts job, CandidateDocumentFacts candidate,
                         Set<DocumentFormat> formats, boolean useLlm,
                         DocumentContactBlock contact) {
        return Hashing.sha256(String.join("|", "RESUME", job.descriptionHash(),
                candidate.sourceHash(), Integer.toString(candidate.profileVersion()),
                job.analysisCacheKey(), documents.resumeTemplateVersion(),
                documents.rendererVersion(), formatIdentity(formats),
                Boolean.toString(useLlm), provider(useLlm), model(useLlm),
                contactIdentities.fingerprint(contact)));
    }

    public String coverNote(JobDocumentFacts job, CandidateDocumentFacts candidate,
                            String resumeContentHash, Set<DocumentFormat> formats,
                            boolean useLlm, DocumentContactBlock contact) {
        return Hashing.sha256(String.join("|", "COVER_NOTE", job.descriptionHash(),
                candidate.sourceHash(), Integer.toString(candidate.profileVersion()),
                job.analysisCacheKey(), resumeContentHash,
                documents.coverNoteTemplateVersion(), documents.rendererVersion(),
                formatIdentity(formats), Boolean.toString(useLlm), provider(useLlm),
                model(useLlm), contactIdentities.fingerprint(contact)));
    }

    public String provider(boolean requested) {
        return requested && llm.enabled() ? llm.provider() : "disabled";
    }

    public String model(boolean requested) {
        return requested && llm.enabled() ? llm.model() : "disabled";
    }

    private String formatIdentity(Set<DocumentFormat> formats) {
        return formats.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
