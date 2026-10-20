package com.jobpilot.personalizedmatching;

import com.jobpilot.jobs.domain.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class VacancyFactExtractor {
    public static final String VERSION="facts-1";
    private static final Pattern YEARS=Pattern.compile("(?i)(?<![\\p{L}\\p{N}.])(?:minimum|min\\.?|at least)?\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s*\\+?\\s*(?:years?|yrs?)(?:\\s+of)?\\s+(?:(professional|commercial|industry)\\s+)?(?:(\\w+(?:\\s+\\w+){0,3})\\s+)?experience");
    private static final Pattern LANG=Pattern.compile("(?i)\\b(English|Romanian|French|German|Spanish|Italian|Portuguese|Dutch|Polish|Ukrainian|Russian|Arabic|Chinese|Japanese)\\b(?:[ :()/\\s-]*(?:(?:level|minimum|at least)\\s+){0,2}(A1|A2|B1|B2|C1|C2|native))?");
    private static final Pattern HARD=Pattern.compile("(?i)\\b(requires?|required|requirements|mandatory|must|minimum|at least|essential|needed|necessary|only)\\b");
    private static final Pattern PREF=Pattern.compile("(?i)\\b(preferred|preferably|desirable|nice to have|bonus|advantage)\\b");
    public VacancyFacts extract(Job j,RawJob raw) {
        String title=raw==null?j.getTitle():raw.title(),body=Objects.toString(raw==null?j.getDescription():raw.description(),"");
        String location=Objects.toString(raw==null?j.getLocation():raw.location(),"");
        RawLocationData structured=raw==null?RawLocationData.empty():raw.locationData();
        if(!structured.structuredLocations().isEmpty()){var locations=new LinkedHashSet<>(structured.structuredLocations());if(!location.isBlank())locations.add(location);location=String.join(" | ",locations);}
        String format=j.getRemoteType().name();
        if(structured.workplaceType()!=null && Set.of("REMOTE","HYBRID","ONSITE").contains(structured.workplaceType().toUpperCase(Locale.ROOT)))format=structured.workplaceType().toUpperCase(Locale.ROOT);
        List<VacancyFacts.Requirement> result=new ArrayList<>();
        for(String s:body.split("(?<=[.!?])\\s+|[\\r\\n;]+")) {
            if(s.isBlank())continue;
            if(result.size()>=200){add(result,"UNPARSED","Requirement extraction limit reached","",true,"Source contains more requirements than this bounded extractor supports");break;}
            int before=result.size();
            String sentence=s.strip();boolean preferred=PREF.matcher(sentence).find();boolean hard=HARD.matcher(sentence).find()&&!preferred;
            boolean recognized=false;
            Matcher skills=Pattern.compile("(?i)(required|preferred|nice to have) skills?\\s*:\\s*(.+)").matcher(sentence);
            if(skills.find()) {
                boolean mandatory=skills.group(1).equalsIgnoreCase("required");
                for(String skill:skills.group(2).replaceAll("[.!]$","").split(",|\\s+and\\s+"))
                    if(!skill.isBlank()){add(result,"SKILL",skill.strip(),"",mandatory?VacancyFacts.Modality.REQUIRED:VacancyFacts.Modality.PREFERRED,sentence);}
                recognized=true;
            }
            Matcher range=Pattern.compile("(?i)(\\d{1,2}(?:\\.\\d{1,2})?)\\s*(?:-|–|to)\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s*years?(?:\\s+of)?\\s+(?:(professional|commercial|industry)\\s+)?experience").matcher(sentence);
            boolean ranged=range.find();
            if(ranged) {
                if(Double.parseDouble(range.group(1))<=Double.parseDouble(range.group(2)))add(result,"EXPERIENCE",range.group(1),range.group(3)==null?"GENERAL":"PROFESSIONAL",hard,sentence);
                else add(result,"UNPARSED",sentence,"",true,sentence);
                recognized=true;
            }
            Matcher years=YEARS.matcher(sentence);
            while(!ranged&&years.find()) {String scope=Objects.toString(years.group(3),"").strip();
                Matcher afterScope=Pattern.compile("(?i)^\\s+(?:with|in)\\s+(.+?)(?:\\s+(?:required|mandatory)|[.!]|$)").matcher(sentence.substring(years.end()));
                if(afterScope.find())scope=afterScope.group(1).strip();
                add(result,"EXPERIENCE",years.group(1),scope.isEmpty()?(years.group(2)==null?"GENERAL":"PROFESSIONAL"):scope,hard,sentence);recognized=true;}
            if(Pattern.compile("(?i)no (?:prior |previous |professional )?experience (?:is )?(?:required|necessary)|experience (?:is )?not required").matcher(sentence).find()) {
                add(result,"EXPERIENCE","0","GENERAL",true,sentence);recognized=true;
            }
            Matcher language=LANG.matcher(sentence);
            if(hard||preferred)while(language.find()) {
                String level=Objects.toString(language.group(2),"").toUpperCase(Locale.ROOT);
                if(level.isEmpty()) {
                    Matcher prefix=Pattern.compile("(?i)(A1|A2|B1|B2|C1|C2|native)\\s+$").matcher(sentence.substring(0,language.start()));
                    if(prefix.find())level=prefix.group(1).toUpperCase(Locale.ROOT);
                }
                if(level.isEmpty() && Pattern.compile("(?i)fluent|fluency|proficient|professional|excellent|advanced|business|working|strong command|good command|native|\\b[ABC][12]\\b").matcher(sentence).find())level="UNSPECIFIED_FLUENCY";
                add(result,"LANGUAGE",language.group(1),level,hard,sentence);recognized=true;
            }
            long languageCount=result.subList(before,result.size()).stream().filter(value->value.kind().equals("LANGUAGE")).count();
            if(languageCount>1 && result.subList(before,result.size()).stream().anyMatch(value->value.kind().equals("LANGUAGE")&&!value.level().isEmpty()))
                for(int i=before;i<result.size();i++){var value=result.get(i);if(value.kind().equals("LANGUAGE")&&value.level().isEmpty())result.set(i,new VacancyFacts.Requirement(value.kind(),value.value(),"UNSPECIFIED_FLUENCY",value.modality(),value.evidence()));}
            if(preferred&&HARD.matcher(sentence).find())add(result,"UNPARSED",sentence,"",true,sentence);
            String country=MatchingText.countryIn(sentence);
            if(Pattern.compile("(?i)(work autho[rz]i[sz]ation|right to work|work permit)").matcher(sentence).find()&&hard) {
                add(result,"AUTHORIZATION",country,"",true,sentence);recognized=true;
            }
            if(Pattern.compile("(?i)(?:no (?:visa )?sponsorship|sponsorship (?:is )?(?:not available|unavailable))").matcher(sentence).find()) {
                add(result,"NO_SPONSORSHIP",country,"",true,sentence);recognized=true;
            }
            if(Pattern.compile("(?i)remote (?:in|within)|residents only|must (?:be based|be located|reside)|[a-z]+ only").matcher(sentence).find()
                    && (MatchingText.contains(sentence,"remote")||MatchingText.contains(sentence,"residents")||sentence.toLowerCase(Locale.ROOT).contains("must"))) {
                add(result,"LOCATION",country,"",true,sentence);recognized=true;
            }
            if(Pattern.compile("(?i)remote (?:worldwide|anywhere)|work from anywhere").matcher(sentence).find()) {
                add(result,"LOCATION","WORLDWIDE","",true,sentence);recognized=true;
            }
            if(hard && Pattern.compile("(?i)degree|bachelor|master|doctorate|licen[sc]e|certification").matcher(sentence).find()) {
                add(result,"EDUCATION",sentence,"",true,sentence);recognized=true;
            }
            if(hard && Pattern.compile("(?i)(?:current|enrolled|university|final.year) student").matcher(sentence).find()) {
                add(result,"STUDENT",sentence,"",true,sentence);recognized=true;
            }
            // Negations, alternatives and mixed clauses cannot be treated as independent requirements.
            boolean alternatives=hard&&Pattern.compile("(?i)\\b(or|unless|except)\\b").matcher(sentence).find();
            boolean negative=Pattern.compile("(?i)(?:not (?:required|mandatory)|no .{0,40}required)").matcher(sentence).find()
                    &&!sentence.matches("(?i)(?:no (?:prior |previous |professional )?experience (?:is )?(?:required|necessary)|experience (?:is )?not required)[.!]?");
            if(alternatives||negative) {
                while(result.size()>before)result.removeLast();
                add(result,"UNPARSED",sentence,"",true,sentence);
                continue;
            }
            if(preferred)for(int n=before;n<result.size();n++){var value=result.get(n);if(value.modality()==VacancyFacts.Modality.UNSPECIFIED)result.set(n,new VacancyFacts.Requirement(value.kind(),value.value(),value.level(),VacancyFacts.Modality.PREFERRED,value.evidence()));}
            if(hard && (!recognized || !fullyUnderstood(sentence,result.subList(before,result.size())) || Pattern.compile("(?i)\\b(or|unless|except|not)\\b").matcher(sentence).find()
                    && !sentence.toLowerCase(Locale.ROOT).contains("experience is not required")))
                add(result,"UNPARSED",sentence,"",true,sentence);
        }
        if(raw!=null&&raw.careerData().hasExperienceRequirement()) {
            RawCareerData career=raw.careerData();String evidence=Objects.toString(career.rawExperienceText(),Objects.toString(career.providerSeniority(),"Provider experience requirement"));
            var modality=career.mandatory()==null?VacancyFacts.Modality.UNSPECIFIED:career.mandatory()?VacancyFacts.Modality.REQUIRED:VacancyFacts.Modality.PREFERRED;
            if(career.minimumYears()!=null&&Double.isFinite(career.minimumYears())&&career.minimumYears()>=0&&career.minimumYears()<=99
                    &&(career.maximumYears()==null||Double.isFinite(career.maximumYears())&&career.maximumYears()>=career.minimumYears())) {
                Matcher scope=YEARS.matcher(evidence);String qualification="UNCONFIRMED_PROVIDER_SCOPE";
                if(scope.find()) {
                    qualification=scope.group(3)!=null?scope.group(3).strip():scope.group(2)!=null?"PROFESSIONAL":"GENERAL";
                    String trailing=evidence.substring(scope.end());
                    Matcher suffix=Pattern.compile("(?i)^\\s+(?:with|in)\\s+(.+?)(?:\\s+(?:required|mandatory)|[.!]|$)").matcher(trailing);
                    if(suffix.find())qualification=suffix.group(1).strip();
                    else if(!boilerplate(trailing).isBlank())qualification="UNCONFIRMED_PROVIDER_SCOPE";
                }
                // Provider codes specify numeric experience, but a seniority label alone does not.
                else if(evidence.matches("(?i)(?:no_experience|none|\\d+ years?|one_year|1_year|one_year_or_less|two_years|2_years|one_to_two_years|three_years|3_years|three_to_four_years|four_years|4_years|more_than_four_years|five_years|5_years)"))qualification="GENERAL";
                add(result,"EXPERIENCE",java.math.BigDecimal.valueOf(career.minimumYears()).stripTrailingZeros().toPlainString(),qualification,modality,evidence);
            } else add(result,"UNPARSED",evidence,"",modality,evidence);
        }
        if(!structured.applicantLocationRequirements().isEmpty())add(result,"LOCATION",countryAlternatives(structured.applicantLocationRequirements()),"",true,String.join(" | ",structured.applicantLocationRequirements()));
        if(!structured.remoteRegions().isEmpty())add(result,"LOCATION",countryAlternatives(structured.remoteRegions()),"",true,String.join(" | ",structured.remoteRegions()));
        if(structured.requiredWorkAuthorization()!=null)add(result,"AUTHORIZATION",MatchingText.countryIn(structured.requiredWorkAuthorization()),"",true,structured.requiredWorkAuthorization());
        if(structured.requiredTimezone()!=null)add(result,"UNPARSED",structured.requiredTimezone(),"",true,structured.requiredTimezone());
        // Legacy city-based inference is not an explicit office requirement for personalized matching.
        boolean worldwideRemote=Arrays.stream(body.split("(?<=[.!?])\\s+|[\\r\\n;]+"))
                .anyMatch(clause->clause.strip().matches("(?i)(?:remote (?:worldwide|anywhere)|work from anywhere)[.!]?"));
        if(worldwideRemote) {
            boolean explicitOffice=Pattern.compile("(?i)\\b(?:on[ -]?site|hybrid|work from (?:our|the) office|work (?:in|at) (?:our|the) office)\\b")
                    .matcher(String.join(" ",body,location,Objects.toString(structured.workplaceType(),""),Objects.toString(structured.jobLocationType(),""))).find();
            format=explicitOffice?"UNKNOWN":"REMOTE";
            if(explicitOffice)add(result,"UNPARSED","Conflicting remote and office workplace requirements","",true,String.join(" | ",body,location,Objects.toString(structured.workplaceType(),""),Objects.toString(structured.jobLocationType(),"")));
        }
        if((format.equals("ONSITE")||format.equals("HYBRID"))&&!location.isBlank())add(result,"OFFICE",structured.structuredLocations().isEmpty()?MatchingText.countryIn(location):countryAlternatives(structured.structuredLocations()),location,true,location);
        String employment=Objects.toString(raw==null?j.getEmploymentType():raw.employmentType(),"").toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');
        VacancyFacts.Salary salary=null;
        Matcher money=Pattern.compile("(?i)salary\\s*:\\s*([A-Z]{3})\\s+(\\d{1,10}(?:\\.\\d{1,2})?)\\s*(?:/|per\\s+)(hour|day|week|month|year)\\b(?:\\s+(gross|net))?").matcher(body);
        if(money.find())try{String currency=money.group(1).toUpperCase(Locale.ROOT);Currency.getInstance(currency);salary=new VacancyFacts.Salary(new java.math.BigDecimal(money.group(2)),currency,money.group(3).toUpperCase(Locale.ROOT),Objects.toString(money.group(4),"UNKNOWN").toUpperCase(Locale.ROOT));}catch(IllegalArgumentException invalid){salary=null;}
        Matcher industry=Pattern.compile("(?i)industry\\s*:\\s*([^.;\\r\\n]{1,120})").matcher(body);
        String sector=industry.find()?industry.group(1).strip():"";
        return new VacancyFacts(title,body,location,format,employment,sector,j.getPublishedAt()==null?j.getFirstSeenAt():j.getPublishedAt(),j.getDeadline(),Pattern.compile("(?i)position closed|no longer accepting applications|vacancy closed|applications are closed").matcher(body).find()?"EXPIRED":j.getStatus().name(),raw==null?"STORED_SOURCE_FIELDS":"PROVIDER_SOURCE_FIELDS",result,salary);
    }
    private String countryAlternatives(List<String> values) {
        Set<String> countries=new TreeSet<>();
        for(String value:values){if(MatchingText.contains(value,"worldwide"))return "WORLDWIDE";String country=MatchingText.countryIn(value);if(country.isEmpty())return "";countries.add(country);}
        return String.join("|",countries);
    }
    /** A recognized token is not proof that every mandatory clause was understood. */
    private boolean fullyUnderstood(String sentence,List<VacancyFacts.Requirement> requirements) {
        if(requirements.stream().anyMatch(r->r.kind().equals("UNPARSED")))return false;
        Set<String> kinds=new HashSet<>();requirements.forEach(r->kinds.add(r.kind()));
        String text=sentence.replaceAll("[.!]$","").strip();
        if(kinds.equals(Set.of("SKILL")))return text.matches("(?i)(?:required|preferred|nice to have) skills?\\s*:.+");
        if(kinds.equals(Set.of("LANGUAGE")))return boilerplate(LANG.matcher(text).replaceAll(" ")).isBlank();
        if(kinds.equals(Set.of("EXPERIENCE"))) {
            if(text.matches("(?i)no (?:prior |previous |professional )?experience (?:is )?(?:required|necessary)|experience (?:is )?not required"))return true;
            String rest=text.replaceAll("(?i)(?<![\\p{L}\\p{N}.])\\d{1,2}(?:\\.\\d{1,2})?\\s*(?:-|–|to)\\s*\\d{1,2}(?:\\.\\d{1,2})?\\s*years?(?:\\s+of)?\\s+(?:(?:professional|commercial|industry)\\s+)?experience"," ");
            return boilerplate(YEARS.matcher(rest).replaceAll(" ")).isBlank();
        }
        if(kinds.equals(Set.of("LOCATION"))) {
            if(text.matches("(?i)(?:remote (?:worldwide|anywhere)|work from anywhere)"))return true;
            String rest=boilerplate(text.replaceAll("(?i)remote|within|residents|be based|be located|reside", " "));
            return !MatchingText.country(rest).isEmpty();
        }
        if(kinds.equals(Set.of("AUTHORIZATION")))return !MatchingText.country(boilerplate(text.replaceAll("(?i)work autho[rz]i[sz]ation|right to work|work permit", " "))).isEmpty();
        if(kinds.equals(Set.of("NO_SPONSORSHIP")))return boilerplate(text.replaceAll("(?i)no (?:visa )?sponsorship|sponsorship (?:is )?(?:not available|unavailable)"," ")).isBlank();
        if(kinds.equals(Set.of("STUDENT")))return boilerplate(text.replaceAll("(?i)(?:current|enrolled|university|final.year) student"," ")).isBlank();
        // Education is compared against the entire statement; extra qualifications cannot disappear.
        return kinds.equals(Set.of("EDUCATION"));
    }
    private String boilerplate(String value) {
        return value.replaceAll("(?i)\\b(?:requires?|required|requirements|mandatory|must|minimum|at least|essential|needed|necessary|only|you|have|is|a|an|at|level|and|in|of)\\b|\\b[ABC][12]\\b|[,:()/-]"," ").replaceAll("\\s+"," ").strip();
    }
    private void add(List<VacancyFacts.Requirement> out,String kind,String value,String level,boolean mandatory,String quote) {
        add(out,kind,value,level,mandatory?VacancyFacts.Modality.REQUIRED:VacancyFacts.Modality.UNSPECIFIED,quote);
    }
    private void add(List<VacancyFacts.Requirement> out,String kind,String value,String level,VacancyFacts.Modality modality,String quote) {
        if(out.size()>=199) {
            if(out.size()==199)out.add(new VacancyFacts.Requirement("UNPARSED","Requirement extraction limit reached","",VacancyFacts.Modality.REQUIRED,"Source contains more requirements than this bounded extractor supports"));
            return;
        }
        var r=new VacancyFacts.Requirement(kind,value.length()>1000?value.substring(0,1000):value,level,modality,quote.length()>1000?quote.substring(0,1000):quote);
        if(!out.contains(r))out.add(r);
    }
}
