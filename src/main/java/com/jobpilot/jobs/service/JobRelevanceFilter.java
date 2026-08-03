package com.jobpilot.jobs.service;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.ScreeningReason;
import com.jobpilot.jobs.domain.ScreeningStage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Signal-based role relevance screening; configured search phrases are boosts, not gates. */
@Component
public class JobRelevanceFilter {
    private static final List<String> ENGINEERING_ROLES = List.of(
            "software engineer", "software developer", "backend engineer", "backend developer",
            "full stack engineer", "full stack developer", "platform engineer",
            "application developer", "api developer", "cloud engineer", "integration engineer",
            "programmer", "software engineering", "backend engineering", "full stack engineering",
            "engineering intern", "engineering internship", "software internship",
            "design engineer", "anti abuse engineer", "deployment engineer", "release engineer",
            "performance engineer", "database engineer", "postgres engineer",
            "postgresql engineer", "multigres engineer");
    private static final List<String> JAVA_SIGNALS = List.of(
            "java", "jvm", "spring", "spring boot", "hibernate", "jpa", "maven", "gradle");
    private static final List<String> BACKEND_SIGNALS = List.of(
            "rest", "rest api", "microservices", "sql", "postgresql", "backend", "server side");
    private static final List<String> SUPPORTING_SIGNALS = List.of(
            "git", "docker", "ci cd", "react", "typescript", "javascript", "cloud", "aws",
            "azure", "kubernetes");
    private static final List<String> GENERIC_ENGINEERING_NOUNS = List.of(
            "engineer", "developer", "programmer", "intern", "internship", "trainee",
            "apprentice", "apprenticeship");
    private static final List<String> NON_ENGINEERING_TITLE_SIGNALS = List.of(
            "accountant", "accounting", "auditor", "audit", "compliance", "governance",
            "marketer", "marketing", "recruiter", "recruiting", "human resources", "finance",
            "legal", "revenue operations", "product marketing", "customer support",
            "support engineer");
    private static final List<String> STRONG_NON_DEVELOPMENT_TITLE_SIGNALS = List.of(
            "brand designer", "brand design", "quant", "quantitative researcher",
            "quantitative analyst", "risk analyst", "risk manager", "growth analyst",
            "data strategy");
    private static final List<String> DEVELOPER_RELATIONS_TITLE_SIGNALS = List.of(
            "developer relations", "devrel", "developer advocate");
    private static final List<String> GROWTH_DATA_TITLE_SIGNALS = List.of("growth data");
    private static final List<String> SALES_ADJACENT_TITLE_SIGNALS = List.of(
            "sales", "pre sales", "sales development", "sales development representative",
            "account executive", "customer success", "business development",
            "solutions consultant");
    private static final List<String> AMBIGUOUS_TECHNICAL_CUSTOMER_ROLES = List.of(
            "solutions engineer", "forward deployed engineer", "implementation engineer",
            "technical consultant", "integration consultant", "customer engineer");
    private static final List<String> COMMERCIAL_RESPONSIBILITY_SIGNALS = List.of(
            "pre sales", "sales ownership", "sales cycle", "sales team", "product demos",
            "customer demos", "demo to prospects", "prospective customers", "account support",
            "customer acquisition", "commercial targets", "solution selling", "revenue targets",
            "sales quota", "pipeline generation", "customer success ownership", "close deals",
            "go to market");
    private static final List<String> SOFTWARE_IMPLEMENTATION_SIGNALS = List.of(
            "write code", "writing code", "software development", "develop software",
            "build software", "build backend", "backend services", "implement backend",
            "design and implement", "production code", "ship code", "test code", "deploy code",
            "java services", "spring boot", "microservices");
    private static final List<String> AMBIGUOUS_IMPLEMENTATION_SIGNALS = List.of(
            "technical implementation", "implement integrations", "build integrations",
            "api integrations", "integration architecture", "configure systems",
            "technical architecture", "hands on implementation");
    private static final List<String> ADVOCACY_RESPONSIBILITY_SIGNALS = List.of(
            "developer advocacy", "advocacy", "developer community", "community building",
            "community engagement", "technical content", "educational content", "content creation",
            "developer education", "events", "workshops", "conferences", "public speaking",
            "evangelism");
    private static final List<String> VISUAL_DESIGN_RESPONSIBILITY_SIGNALS = List.of(
            "visual design", "brand design", "graphic design", "product design", "brand identity",
            "visual assets", "figma", "illustration", "typography");

    private final List<String> searchTerms;

    public JobRelevanceFilter(JobPilotProperties properties) {
        this.searchTerms = properties.searchTerms().stream().map(this::normalize).toList();
    }

    public RelevanceDecision evaluate(RawJob job) {
        if (job == null) throw new IllegalArgumentException("Raw job is required");
        String title = normalize(job.title());
        String description = normalize(job.description());
        String text = (title + " " + description).strip();
        List<String> nonEngineering = signals(title, NON_ENGINEERING_TITLE_SIGNALS);
        List<String> strongNonDevelopment = signals(title, STRONG_NON_DEVELOPMENT_TITLE_SIGNALS);
        List<String> developerRelations = signals(title, DEVELOPER_RELATIONS_TITLE_SIGNALS);
        List<String> growthData = signals(title, GROWTH_DATA_TITLE_SIGNALS);
        List<String> salesAdjacentTitle = signals(title, SALES_ADJACENT_TITLE_SIGNALS);
        List<String> ambiguousRoles = signals(title, AMBIGUOUS_TECHNICAL_CUSTOMER_ROLES);
        List<String> titleRoles = signals(title, ENGINEERING_ROLES);
        List<String> genericRoleNouns = signals(title, GENERIC_ENGINEERING_NOUNS);
        List<String> java = signals(text, JAVA_SIGNALS);
        List<String> titleJava = signals(title, JAVA_SIGNALS);
        List<String> backend = signals(description, BACKEND_SIGNALS);
        List<String> supporting = signals(text, SUPPORTING_SIGNALS);
        List<String> boosts = signals(text, searchTerms);
        List<String> softwareImplementation = signals(description, SOFTWARE_IMPLEMENTATION_SIGNALS);

        if (!strongNonDevelopment.isEmpty()) {
            return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                    "Title indicates a non-development primary function: "
                            + String.join(", ", strongNonDevelopment));
        }
        if (!developerRelations.isEmpty()
                && (softwareImplementation.isEmpty()
                || !signals(description, ADVOCACY_RESPONSIBILITY_SIGNALS).isEmpty())) {
            return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                    "Developer-relations title lacks exclusive production software implementation "
                            + "ownership: " + String.join(", ", developerRelations));
        }
        if (!growthData.isEmpty() && softwareImplementation.isEmpty() && titleRoles.isEmpty()) {
            return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                    "Growth/data title lacks hands-on software implementation ownership: "
                            + String.join(", ", growthData));
        }
        if (phrase(title, "design engineer")
                && !signals(description, VISUAL_DESIGN_RESPONSIBILITY_SIGNALS).isEmpty()) {
            return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                    "Design Engineer responsibilities are primarily visual, brand, graphic, or "
                            + "product design");
        }

        if (!salesAdjacentTitle.isEmpty()) {
            return decision(ScreeningDisposition.REJECT, "SALES_ADJACENT_TECHNICAL_ROLE",
                    "Title indicates a sales-adjacent primary function: "
                            + String.join(", ", salesAdjacentTitle));
        }
        if (!nonEngineering.isEmpty()) {
            return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                    "Title indicates a non-engineering primary function: "
                            + String.join(", ", nonEngineering));
        }
        if (!ambiguousRoles.isEmpty()) {
            return ambiguousTechnicalCustomerRole(description, ambiguousRoles, java, backend,
                    supporting);
        }
        if (!genericRoleNouns.isEmpty() && !titleJava.isEmpty()) {
            return decision(ScreeningDisposition.MATCH, "SOFTWARE_DEVELOPMENT_ROLE",
                    "Java/JVM-oriented engineering role: " + String.join(", ", titleJava));
        }
        if (!titleRoles.isEmpty()) {
            if (!java.isEmpty()) {
                return decision(ScreeningDisposition.MATCH, "SOFTWARE_DEVELOPMENT_ROLE",
                        "Engineering role with Java/JVM signal: " + String.join(", ", java));
            }
            if (!backend.isEmpty()) {
                return decision(ScreeningDisposition.MATCH, "SOFTWARE_DEVELOPMENT_ROLE",
                        "Engineering role with backend technology signal: "
                                + String.join(", ", backend));
            }
            String support = supporting.isEmpty() ? "" : "; supporting signals: "
                    + String.join(", ", supporting);
            String boost = boosts.isEmpty() ? "" : "; configured search-term boost present";
            return decision(ScreeningDisposition.REVIEW, "SOFTWARE_DEVELOPMENT_ROLE",
                    "Software/backend role lacks a decisive Java or backend technology signal"
                            + support + boost);
        }
        if (!java.isEmpty() || !backend.isEmpty()) {
            List<String> technology = new ArrayList<>(java);
            technology.addAll(backend);
            return decision(ScreeningDisposition.REVIEW, "AMBIGUOUS_IMPLEMENTATION_ROLE",
                    "Relevant backend technology appears in a role with an ambiguous title: "
                            + String.join(", ", technology));
        }
        return decision(ScreeningDisposition.REJECT, "NO_ENGINEERING_EVIDENCE",
                "No engineering-role or software-development evidence was found");
    }

    private RelevanceDecision ambiguousTechnicalCustomerRole(
            String description, List<String> roles, List<String> java, List<String> backend,
            List<String> supporting) {
        List<String> commercial = signals(description, COMMERCIAL_RESPONSIBILITY_SIGNALS);
        List<String> softwareImplementation = signals(description, SOFTWARE_IMPLEMENTATION_SIGNALS);
        List<String> ambiguousImplementation = signals(
                description, AMBIGUOUS_IMPLEMENTATION_SIGNALS);
        boolean clearSoftwareDevelopment = !softwareImplementation.isEmpty()
                && (!java.isEmpty() || !backend.isEmpty());
        boolean commerciallyDominated = commercial.size() >= 2
                || signals(description, List.of("pre sales", "sales ownership", "solution selling",
                "sales quota", "commercial targets")).size() >= 1;

        if (commerciallyDominated) {
            return decision(ScreeningDisposition.REJECT, "SALES_ADJACENT_TECHNICAL_ROLE",
                    "Ambiguous technical-customer title is dominated by commercial duties: "
                            + String.join(", ", commercial));
        }
        if (clearSoftwareDevelopment && !java.isEmpty()) {
            return decision(ScreeningDisposition.MATCH, "SOFTWARE_DEVELOPMENT_ROLE",
                    "Ambiguous technical-customer title has decisive hands-on Java/JVM "
                            + "implementation evidence: " + String.join(", ", java));
        }
        if (clearSoftwareDevelopment || !ambiguousImplementation.isEmpty()) {
            return decision(ScreeningDisposition.REVIEW, "AMBIGUOUS_IMPLEMENTATION_ROLE",
                    "Technical implementation role has uncertain primary software-development "
                            + "scope: " + String.join(", ", roles));
        }
        if (!java.isEmpty() || !backend.isEmpty() || !supporting.isEmpty()) {
            return decision(ScreeningDisposition.REVIEW, "AMBIGUOUS_IMPLEMENTATION_ROLE",
                    "Technical-customer role mentions relevant technology without decisive "
                            + "hands-on development ownership: " + String.join(", ", roles));
        }
        return decision(ScreeningDisposition.REJECT, "NON_ENGINEERING_PRIMARY_FUNCTION",
                "Ambiguous technical-customer title lacks software implementation evidence: "
                        + String.join(", ", roles));
    }

    /** Compatibility helper for callers that only need the hard-rejection boundary. */
    public boolean isRelevant(RawJob job) {
        return evaluate(job).disposition() != ScreeningDisposition.REJECT;
    }

    private RelevanceDecision decision(ScreeningDisposition disposition, String code, String message) {
        return new RelevanceDecision(disposition,
                List.of(new ScreeningReason(ScreeningStage.ROLE_RELEVANCE, code, message)));
    }

    private List<String> signals(String text, List<String> candidates) {
        return candidates.stream().filter(candidate -> phrase(text, candidate)).distinct().toList();
    }

    private boolean phrase(String text, String phrase) {
        if (phrase == null || phrase.isBlank()) return false;
        String regex = "(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase)
                .replace("\\Q \\E", "\\E\\s+\\Q")
                + "(?![\\p{L}\\p{N}])";
        // `phrase` comes from candidate-profile configuration, never from vacancy text.
        return ScreeningPatterns.caseInsensitive(regex).matcher(text).find();
    }

    private static final Pattern RELEVANCE_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern RELEVANCE_NON_TOKEN = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern RELEVANCE_WHITESPACE = Pattern.compile("\\s+");

    private String normalize(String value) {
        if (value == null) return "";
        String decomposed = RELEVANCE_MARKS
                .matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        String collapsed = RELEVANCE_NON_TOKEN
                .matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return RELEVANCE_WHITESPACE.matcher(collapsed).replaceAll(" ").strip();
    }
}
