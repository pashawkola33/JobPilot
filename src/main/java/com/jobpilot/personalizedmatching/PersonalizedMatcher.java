package com.jobpilot.personalizedmatching;

import com.jobpilot.searchprofile.SearchProfile;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class PersonalizedMatcher {
    public static final String VERSION="compatibility-1";
    public enum Eligibility {ELIGIBLE,INELIGIBLE,UNKNOWN}
    public record Reason(String code,String dimension,String status,String explanation,String evidence,String candidateField) { }
    public record Match(Eligibility eligibility,int compatibilityScore,Integer recommendationScore,int evidenceCoverage,
                        Map<String,Double> factors,List<Reason> reasons) { }
    public Match evaluate(SearchProfile p,VacancyFacts f,Instant now) {
        List<Reason> checks=new ArrayList<>();
        Set<String> skills=new HashSet<>();p.technicalSkills().forEach(x->skills.add(MatchingText.skill(x)));p.nontechnicalSkills().forEach(x->skills.add(MatchingText.skill(x)));
        for(var r:f.requirements()) {
            if(r.mandatory())checks.add(check(p,r,skills));
            else if(r.modality()==VacancyFacts.Modality.UNSPECIFIED)checks.add(reason("REQUIREMENT_MODALITY_UNKNOWN",r.kind(),"UNKNOWN","The source does not establish whether this requirement is mandatory or preferred",r.evidence(),""));
        }
        if(f.status().equals("EXPIRED") || f.deadline()!=null&&!f.deadline().isAfter(now))checks.add(reason("VACANCY_CLOSED","STATUS","BLOCKED","Vacancy is closed or expired",Objects.toString(f.deadline(),f.status()),""));
        if(f.requirements().isEmpty())checks.add(reason("JOB_REQUIREMENTS_UNKNOWN","REQUIREMENTS","UNKNOWN","No supported requirements could be established","",""));
        if(f.workFormat().equals("REMOTE") && f.requirements().stream().noneMatch(x->x.kind().equals("LOCATION")))
            checks.add(reason("REMOTE_SCOPE_UNKNOWN","LOCATION","UNKNOWN","Remote work does not establish permitted applicant locations",f.location(),"currentCountry"));
        boolean blocked=checks.stream().anyMatch(x->x.status().equals("BLOCKED"));
        Eligibility eligibility=blocked?Eligibility.INELIGIBLE:checks.stream().anyMatch(x->x.status().equals("UNKNOWN"))?Eligibility.UNKNOWN:Eligibility.ELIGIBLE;
        double role=role(p,f.title());
        List<String> required=f.requirements().stream().filter(x->x.kind().equals("SKILL")&&x.mandatory()).map(x->MatchingText.skill(x.value())).distinct().toList();
        List<String> preferred=f.requirements().stream().filter(x->x.kind().equals("SKILL")&&x.modality()==VacancyFacts.Modality.PREFERRED).map(x->MatchingText.skill(x.value())).distinct().toList();
        double skill=required.isEmpty()?(preferred.isEmpty()?.5:coverage(preferred,skills)):preferred.isEmpty()?coverage(required,skills):.8*coverage(required,skills)+.2*coverage(preferred,skills);
        var exp=f.requirements().stream().filter(x->x.kind().equals("EXPERIENCE")&&x.modality()!=VacancyFacts.Modality.UNSPECIFIED).map(x->check(p,x,skills)).toList();
        double experience=exp.isEmpty()?.5:exp.stream().anyMatch(x->x.status().equals("BLOCKED"))?0:exp.stream().anyMatch(x->x.status().equals("UNKNOWN"))?.5:1;
        int advertisedLevel=seniority(f.title());int savedLevel=switch(p.experienceLevel()){case NO_PROFESSIONAL_EXPERIENCE,ENTRY->0;case MID->1;case SENIOR->2;case LEAD->3;case UNKNOWN->-1;};
        boolean levelKnown=exp.isEmpty()&&advertisedLevel>=0&&savedLevel>=0;
        if(levelKnown)experience=savedLevel>=advertisedLevel?1:0;
        double geo=p.targetRegions().isEmpty()||f.location().isBlank()?.5:p.targetRegions().stream().anyMatch(x->MatchingText.contains(f.location(),x)||!MatchingText.country(x).isEmpty()&&MatchingText.country(x).equals(MatchingText.countryIn(f.location())))?1:0;
        double format=f.workFormat().equals("UNKNOWN")||p.workFormats().isEmpty()?.5:p.workFormats().stream().anyMatch(x->x.name().equals(f.workFormat()))?1:0;
        double employment=f.employmentType().isBlank()||p.employmentTypes().isEmpty()?.5:p.employmentTypes().stream().anyMatch(x->x.name().equals(f.employmentType()))?1:0;
        double industry=f.industry().isBlank()||p.industries().isEmpty()?.5:p.industries().stream().anyMatch(x->MatchingText.contains(f.industry(),x))?1:0;
        double salary=.5;
        if(p.salary()!=null&&f.salary()!=null&&p.salary().currency().equals(f.salary().currency())&&p.salary().period().name().equals(f.salary().period())&&p.salary().basis()!=SearchProfile.SalaryBasis.UNKNOWN&&p.salary().basis().name().equals(f.salary().basis()))salary=f.salary().amount().compareTo(p.salary().amount())>=0?1:0;
        Map<String,Double> factors=new LinkedHashMap<>();factors.put("role",35*role);factors.put("skills",35*skill);factors.put("experience",15*experience);factors.put("preferences",3*(geo+format+employment+industry+salary));
        int total=(int)Math.round(factors.values().stream().mapToDouble(Double::doubleValue).sum());
        // Coverage counts established dimensions/checks; neutral values are not satisfied evidence.
        double known=35+(required.isEmpty()&&preferred.isEmpty()?0:35*skill)+(levelKnown?15:exp.isEmpty()||exp.stream().anyMatch(x->x.status().equals("UNKNOWN"))?0:15)
                +(geo==.5?0:3)+(format==.5?0:3)+(employment==.5?0:3)+(industry==.5?0:3)+(salary==.5?0:3);
        long uncertain=checks.stream().filter(x->x.status().equals("UNKNOWN")).count();
        int evidence=(int)Math.round(known*(checks.isEmpty()?0:(double)(checks.size()-uncertain)/checks.size()));
        if(levelKnown)checks.add(reason("EXPERIENCE_LEVEL_PREFERENCE","EXPERIENCE","PREFERENCE",experience==1?"Saved experience level is compatible with advertised seniority":"Advertised seniority is higher than the saved experience level; this affects ranking, not hard eligibility",f.title(),"experienceLevel"));
        checks.add(reason("ROLE_COMPATIBILITY","ROLE","PREFERENCE","Desired-role coverage is "+Math.round(role*100)+"% of normalized title terms",f.title(),"desiredTitles/roleCategories"));
        for(var requirement:f.requirements())if(requirement.modality()==VacancyFacts.Modality.PREFERRED)
            checks.add(reason("PREFERRED_REQUIREMENT","PREFERENCE","PREFERENCE","This is a preferred factor; it cannot create a hard blocker",requirement.evidence(),"saved search profile (self-reported)"));
        checks.add(reason("COMPATIBILITY_MODEL","SCORING","PREFERENCE","Deterministic compatibility, not a hiring probability; unstated factors use a labelled neutral half-value","","saved search profile (self-reported)"));
        return new Match(eligibility,total,blocked?null:total,evidence,Collections.unmodifiableMap(factors),List.copyOf(checks));
    }
    private Reason check(SearchProfile p,VacancyFacts.Requirement r,Set<String> skills) {
        String state="UNKNOWN",code=r.kind()+"_UNKNOWN",message="Requirement needs confirmation",field="";
        switch(r.kind()) {
            case "SKILL" -> {field="technicalSkills/nontechnicalSkills";state=skills.contains(MatchingText.skill(r.value()))?"SATISFIED":"UNKNOWN";code=state.equals("SATISFIED")?"REQUIRED_SKILL_SATISFIED":"REQUIRED_SKILL_UNCONFIRMED";message=state.equals("SATISFIED")?"Required skill "+r.value()+" is listed in the self-reported profile":"Required skill "+r.value()+" is not confirmed by the saved profile";}
            case "EXPERIENCE" -> {
                field="experienceYears/statedExperience/experienceLevel";BigDecimal years=confirmedYears(p,r.level());
                code="EXPERIENCE_SCOPE_UNKNOWN";
                if(years!=null||r.level().equals("GENERAL")) {
                    if(new BigDecimal(r.value()).signum()==0){state="SATISFIED";code="EXPERIENCE_SATISFIED";}
                    else if(years!=null){state=years.compareTo(new BigDecimal(r.value()))>=0?"SATISFIED":"BLOCKED";code=state.equals("BLOCKED")?"EXPERIENCE_BELOW_REQUIRED":"EXPERIENCE_SATISFIED";}
                }
                message=state.equals("SATISFIED")?(new BigDecimal(r.value()).signum()==0?"Source explicitly requires no prior experience; candidate years are not assumed":"Saved self-reported experience meets the comparable requirement"):state.equals("BLOCKED")?"Stated experience is below the mandatory minimum":"Comparable experience in the required scope is unconfirmed";
            }
            case "LANGUAGE" -> {
                field="languages";var levels=p.languages().stream().filter(x->MatchingText.normalized(x.language()).equals(MatchingText.normalized(r.value()))).map(SearchProfile.Language::proficiency).distinct().toList();
                if(levels.size()==1&&levels.getFirst()!=SearchProfile.Proficiency.UNKNOWN&&!r.level().equals("UNSPECIFIED_FLUENCY")&&!r.level().isEmpty()) {
                    int required=level(r.level());int actual=level(levels.getFirst().name());
                    if(r.level().equals("NATIVE"))state=levels.getFirst()==SearchProfile.Proficiency.NATIVE?"SATISFIED":"UNKNOWN";
                    else if(required>0){state=actual>=required?"SATISFIED":"BLOCKED";}
                }
                code=state.equals("BLOCKED")?"LANGUAGE_BELOW_REQUIRED":state.equals("SATISFIED")?"LANGUAGE_SATISFIED":"LANGUAGE_LEVEL_UNKNOWN";
                message=state.equals("BLOCKED")?"Stated language level is below the mandatory level":state.equals("SATISFIED")?"Stated language level meets the requirement":"Required language proficiency needs confirmation";
            }
            case "AUTHORIZATION" -> {
                field="authorizationByCountry";var grant=p.authorizationByCountry().stream().filter(x->x.country().equals(r.value())).findFirst();
                if(grant.isPresent())state=switch(grant.get().status()){case AUTHORIZED->"SATISFIED";case NOT_AUTHORIZED->"BLOCKED";case UNKNOWN,REQUIRES_SPONSORSHIP->"UNKNOWN";};
                code=state.equals("SATISFIED")?"WORK_AUTHORIZATION_SATISFIED":state.equals("BLOCKED")?"WORK_AUTHORIZATION_INCOMPATIBLE":"WORK_AUTHORIZATION_UNKNOWN";
                message=state.equals("SATISFIED")?"Profile explicitly states authorization for the required country":state.equals("BLOCKED")?"Profile explicitly states no authorization for the required country":"Country-specific authorization is unconfirmed; residence does not prove authorization";
            }
            case "NO_SPONSORSHIP" -> {
                field="authorizationByCountry/workAuthorization";var grant=p.authorizationByCountry().stream().filter(x->x.country().equals(r.value())).findFirst();
                var status=grant.map(SearchProfile.Authorization::status).orElse(SearchProfile.WorkAuthorization.UNKNOWN);
                if(status==SearchProfile.WorkAuthorization.REQUIRES_SPONSORSHIP)state="BLOCKED";
                else if(grant.isPresent()&&status==SearchProfile.WorkAuthorization.AUTHORIZED)state="SATISFIED";
                code=state.equals("BLOCKED")?"SPONSORSHIP_UNAVAILABLE":"SPONSORSHIP_"+state;message=state.equals("BLOCKED")?"Sponsorship is required by the candidate and unavailable for this vacancy":"Whether sponsorship is needed must be confirmed for this country";
            }
            case "LOCATION","OFFICE" -> {
                field="currentCountry/currentCity/relocation";String home=MatchingText.country(p.currentCountry());
                if(r.value().equals("WORLDWIDE"))state="SATISFIED";
                else if(!r.value().isEmpty()&&!home.isEmpty()) {
                    if(Arrays.asList(r.value().split("\\|")).contains(home)) {
                        state="SATISFIED";
                        if(!p.currentCity().isBlank()&&!MatchingText.country(p.currentCity()).isEmpty()&&!MatchingText.country(p.currentCity()).equals(home))state="UNKNOWN";
                        if(state.equals("SATISFIED")&&r.kind().equals("OFFICE")) {
                            if(p.currentCity().isBlank())state="UNKNOWN";
                            else if(!MatchingText.contains(r.level(),p.currentCity()))state=p.relocation()==SearchProfile.Choice.NO?"BLOCKED":"UNKNOWN";
                        }
                    } else state=p.relocation()==SearchProfile.Choice.NO||r.kind().equals("LOCATION")?"BLOCKED":"UNKNOWN";
                }
                code=state.equals("SATISFIED")?"LOCATION_SATISFIED":state.equals("BLOCKED")?"LOCATION_RESTRICTED":"LOCATION_UNKNOWN";
                message=state.equals("SATISFIED")?"Stated location is compatible with the stored restriction":state.equals("BLOCKED")?"Stated location conflicts with the mandatory restriction":"Required location or relocation needs confirmation";
            }
            case "STUDENT" -> {field="studentStatus";if(p.studentStatus()==SearchProfile.StudentStatus.NOT_STUDENT)state="BLOCKED";else if(p.studentStatus()==SearchProfile.StudentStatus.STUDENT&&!MatchingText.contains(r.value(),"final year"))state="SATISFIED";code="STUDENT_"+state;message="Required student status evaluated only from the saved explicit statement";}
            case "EDUCATION" -> {
                field="education";String required=r.value().replaceAll("(?i)^(?:must have|requires?|an?)\\s+","").replaceAll("(?i)\\s+(?:is )?(?:required|mandatory)[.!]?$","");
                if(!required.isBlank()&&MatchingText.normalized(p.education()).equals(MatchingText.normalized(required))){state="SATISFIED";code="EDUCATION_SATISFIED";}
                else if(java.util.regex.Pattern.compile("(?i)\\b(?:degree|bachelor(?:\'s)?|doctorate|master(?:\'s)? degree)\\b").matcher(r.value()).find()&&Set.of("no degree","no university degree","no formal degree").contains(MatchingText.normalized(p.education()))){state="BLOCKED";code="EDUCATION_INCOMPATIBLE";}
                else code="EDUCATION_UNCONFIRMED";
                message=state.equals("SATISFIED")?"Saved self-reported education explicitly matches the stated qualification":state.equals("BLOCKED")?"Candidate explicitly states no degree while a degree is mandatory":"Mandatory education or licence needs an explicit comparable qualification; no equivalence is assumed";
            }
            default -> {code="MANDATORY_REQUIREMENT_UNPARSED";message="An explicit hard requirement could not be safely interpreted";}
        }
        return reason(code,r.kind(),state,message,r.evidence(),field);
    }
    private BigDecimal confirmedYears(SearchProfile p,String scope) {
        if(scope.equals("GENERAL"))return p.experienceYears();
        if(scope.equals("PROFESSIONAL")&&(p.experienceLevel()==SearchProfile.ExperienceLevel.NO_PROFESSIONAL_EXPERIENCE||p.experienceYears()!=null&&p.experienceYears().signum()==0))return BigDecimal.ZERO;
        String stated=MatchingText.normalized(p.statedExperience());
        String qualification=scope.equals("PROFESSIONAL")?"(?:professional|commercial|industry)":java.util.regex.Pattern.quote(MatchingText.normalized(scope));
        var explicit=java.util.regex.Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})?) years? (?:of )?"+qualification+" experience[.]?$").matcher(stated);
        if(explicit.matches()) {BigDecimal years=new BigDecimal(explicit.group(1));if(p.experienceYears()==null||years.compareTo(p.experienceYears())<=0)return years;}
        return null;
    }
    private int level(String s){return switch(s){case "A1"->1;case "A2"->2;case "B1"->3;case "B2"->4;case "C1"->5;case "C2","NATIVE"->6;default->0;};}
    private Reason reason(String code,String dimension,String state,String text,String quote,String field){return new Reason(code,dimension,state,text,quote,field);}
    private double coverage(List<String> required,Set<String> actual){return required.stream().filter(actual::contains).count()/(double)required.size();}
    private int seniority(String title) {
        String text=MatchingText.normalized(title);Set<Integer> levels=new HashSet<>();
        if(java.util.regex.Pattern.compile("\\b(intern|internship|trainee|junior|entry level|graduate)\\b").matcher(text).find())levels.add(0);
        if(java.util.regex.Pattern.compile("\\b(mid level|middle|medior)\\b").matcher(text).find())levels.add(1);
        if(java.util.regex.Pattern.compile("\\b(senior|staff|principal)\\b").matcher(text).find())levels.add(2);
        if(java.util.regex.Pattern.compile("\\b(lead(?! generation)|director|head)\\b").matcher(text).find())levels.add(3);
        return levels.size()==1?levels.iterator().next():-1;
    }
    private double role(SearchProfile p,String title) {
        Set<String> tokens=new HashSet<>(Arrays.asList(MatchingText.normalized(title).split(" ")));tokens.removeAll(Set.of("junior","senior","entry","level","intern","graduate","the","a","and","of"));
        if(tokens.isEmpty())return 0;
        List<String> desired=new ArrayList<>(p.desiredTitles());desired.addAll(p.roleCategories());
        return desired.stream().mapToDouble(x->{Set<String> wanted=new HashSet<>(Arrays.asList(MatchingText.normalized(x).split(" ")));long matched=tokens.stream().filter(wanted::contains).count();int jobLevel=seniority(title),desiredLevel=seniority(x);
            if(jobLevel>=0&&desiredLevel>=0)return (matched+(jobLevel==desiredLevel?1:0))/(double)(tokens.size()+1);
            return matched/(double)tokens.size();}).max().orElse(0);
    }
}
