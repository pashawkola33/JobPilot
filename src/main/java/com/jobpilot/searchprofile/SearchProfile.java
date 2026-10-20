package com.jobpilot.searchprofile;

import com.jobpilot.identity.IdentityException;
import java.math.BigDecimal;
import java.util.*;

/** Self-stated preferences, never evidence for a résumé or a qualification. */
public record SearchProfile(
        List<String> desiredTitles, List<String> roleCategories, List<String> industries,
        List<String> technicalSkills, List<String> nontechnicalSkills,
        ExperienceLevel experienceLevel, String statedExperience, BigDecimal experienceYears,
        String currentCountry, String currentCity, List<String> targetRegions,
        List<WorkFormat> workFormats, List<EmploymentType> employmentTypes, List<Language> languages,
        WorkAuthorization workAuthorization, String authorizationDetails, Salary salary,
        String education, StudentStatus studentStatus, Choice relocation, String additionalPreferences, List<Authorization> authorizationByCountry) {
    public enum ExperienceLevel { UNKNOWN, NO_PROFESSIONAL_EXPERIENCE, ENTRY, MID, SENIOR, LEAD }
    public enum WorkFormat { ONSITE, HYBRID, REMOTE }
    public enum EmploymentType { FULL_TIME, PART_TIME, INTERNSHIP, CONTRACT, TEMPORARY, APPRENTICESHIP, VOLUNTEER, OTHER }
    public enum Proficiency { UNKNOWN, A1, A2, B1, B2, C1, C2, NATIVE }
    public enum WorkAuthorization { UNKNOWN, AUTHORIZED, REQUIRES_SPONSORSHIP, NOT_AUTHORIZED }
    public enum StudentStatus { UNKNOWN, STUDENT, NOT_STUDENT }
    public enum Choice { UNKNOWN, YES, NO }
    public enum SalaryPeriod { HOUR, DAY, WEEK, MONTH, YEAR }
    public record Authorization(String country, WorkAuthorization status) {
        public Authorization {
            country=text(country,2).toUpperCase(Locale.ROOT);
            if(!Arrays.asList(Locale.getISOCountries()).contains(country))throw invalid();
            status=unknown(status,WorkAuthorization.UNKNOWN);
        }
    }
    public record Language(String language, Proficiency proficiency) {
        public Language {language=text(language,120);if(language.isEmpty())throw invalid();proficiency=unknown(proficiency,Proficiency.UNKNOWN);}
    }
    public enum SalaryBasis { UNKNOWN, GROSS, NET }
    public record Salary(BigDecimal amount, String currency, SalaryPeriod period, SalaryBasis basis) {
        public Salary {
            basis=unknown(basis,SalaryBasis.UNKNOWN);
            if(amount==null || amount.signum()<=0 || amount.compareTo(new BigDecimal("1000000000"))>0 || amount.scale()>2 || period==null)throw invalid();
            currency=text(currency,3).toUpperCase(Locale.ROOT);
            try {Currency.getInstance(currency);} catch(IllegalArgumentException error){throw invalid();}
        }
    }
    public SearchProfile {
        desiredTitles=strings(desiredTitles);roleCategories=strings(roleCategories);industries=strings(industries);
        technicalSkills=strings(technicalSkills);nontechnicalSkills=strings(nontechnicalSkills);
        experienceLevel=unknown(experienceLevel,ExperienceLevel.UNKNOWN);statedExperience=text(statedExperience,2000);
        if(experienceYears!=null && (experienceYears.signum()<0 || experienceYears.compareTo(new BigDecimal("80"))>0 || experienceYears.scale()>2))throw invalid();
        currentCountry=text(currentCountry,120);currentCity=text(currentCity,120);targetRegions=strings(targetRegions);
        workFormats=values(workFormats,3);employmentTypes=values(employmentTypes,8);languages=values(languages,20);
        workAuthorization=unknown(workAuthorization,WorkAuthorization.UNKNOWN);authorizationDetails=text(authorizationDetails,1000);
        education=text(education,1000);studentStatus=unknown(studentStatus,StudentStatus.UNKNOWN);
        relocation=unknown(relocation,Choice.UNKNOWN);additionalPreferences=text(additionalPreferences,2000);
        authorizationByCountry=values(authorizationByCountry,40);
        if(authorizationByCountry.stream().map(Authorization::country).distinct().count()!=authorizationByCountry.size())throw invalid();
    }
    public List<String> missingRequiredFields() {
        List<String> missing=new ArrayList<>();
        if(desiredTitles.isEmpty() && roleCategories.isEmpty())missing.add("desiredRoles");
        if(targetRegions.isEmpty())missing.add("targetRegions");
        if(workFormats.isEmpty())missing.add("workFormats");
        return List.copyOf(missing);
    }
    public static SearchProfile empty() {return new SearchProfile(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);}
    private static <T> T unknown(T value,T unknown){return value==null?unknown:value;}
    private static <T> List<T> values(List<T> input,int max) {
        if(input==null)return List.of();
        if(input.size()>max || input.stream().anyMatch(Objects::isNull))throw invalid();
        return List.copyOf(new LinkedHashSet<>(input));
    }
    private static List<String> strings(List<String> input) {
        List<String> result=new ArrayList<>();
        for(String item:values(input,40)) {String normalized=text(item,120);if(normalized.isEmpty())throw invalid();result.add(normalized);}
        return List.copyOf(new LinkedHashSet<>(result));
    }
    private static String text(String value,int max) {
        if(value==null)return "";
        String text=value.strip();
        if(text.length()>max || text.chars().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))throw invalid();
        return text;
    }
    private static IdentityException invalid(){return new IdentityException(400,"INVALID_SEARCH_PROFILE");}
}
