package com.jobpilot.personalizedmatching;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

final class MatchingText {
    private MatchingText() { }
    static String normalized(String text) {
        return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFKD).replaceAll("\\p{M}+","")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#.]+"," ").strip().replaceAll("\\s+"," ");
    }
    static String skill(String text) {
        String n=normalized(text);
        return switch(n) {case "postgres"->"postgresql";case "google analytics 4","ga4"->"google analytics";case "search engine optimization"->"seo";default->n;};
    }
    static boolean contains(String text,String phrase) {
        if(phrase==null || phrase.isBlank())return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}+#])"+Pattern.quote(normalized(phrase))+"(?![\\p{L}\\p{N}+#])").matcher(normalized(text)).find();
    }
    private static final Map<String,String> COUNTRIES=countryNames();
    private static Map<String,String> countryNames() {
        Map<String,String> values=new LinkedHashMap<>();
        for(String code:Locale.getISOCountries()){values.put(normalized(code),code);values.put(normalized(new Locale("",code).getDisplayCountry(Locale.ENGLISH)),code);}
        values.put("uk","GB");values.put("usa","US");return Map.copyOf(values);
    }
    static String country(String value) {
        String n=normalized(value).replaceAll("[.]+$", "");String code=COUNTRIES.get(n);if(code!=null)return code;
        return switch(n){case "bucharest","bucuresti","cluj napoca","timisoara"->"RO";case "berlin","munich"->"DE";case "paris"->"FR";case "london"->"GB";default->"";};
    }
    static String countryIn(String text) {
        String exact=country(text);if(!exact.isEmpty())return exact;
        String haystack=" "+normalized(text).replaceAll("[.]", " ").replaceAll("\\s+", " ")+" ";Set<String> found=new TreeSet<>();
        for(var value:COUNTRIES.entrySet())if(value.getKey().length()>3&&haystack.contains(" "+value.getKey()+" "))found.add(value.getValue());
        if(java.util.regex.Pattern.compile("\\bUS(?:A)? only\\b").matcher(Objects.toString(text,"")).find())found.add("US");
        if(java.util.regex.Pattern.compile("\\bUK only\\b").matcher(Objects.toString(text,"")).find())found.add("GB");
        return found.size()==1?found.iterator().next():"";
    }
}
