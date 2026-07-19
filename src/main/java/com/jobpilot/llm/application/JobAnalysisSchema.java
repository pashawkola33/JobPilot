package com.jobpilot.llm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JobAnalysisSchema {
    private static final String SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "roleSummary":{"type":"string","minLength":1,"maxLength":500},
                "mustHaveRequirements":{"$ref":"#/$defs/stringList"},
                "preferredRequirements":{"$ref":"#/$defs/stringList"},
                "responsibilities":{"$ref":"#/$defs/stringList"},
                "experienceRequirement":{"type":["string","null"],"maxLength":1000},
                "educationRequirement":{"type":["string","null"],"maxLength":1000},
                "languageRequirement":{"type":["string","null"],"maxLength":1000},
                "locationConstraints":{"type":["string","null"],"maxLength":1000},
                "workAuthorizationSignals":{"type":["string","null"],"maxLength":1000},
                "candidateStrengths":{
                  "type":"array","maxItems":50,
                  "items":{"type":"object","additionalProperties":false,
                    "properties":{
                      "factKey":{"type":"string","minLength":1,"maxLength":100},
                      "matchStrength":{"type":"string","enum":["MATCH","PARTIAL_MATCH"]}
                    },"required":["factKey","matchStrength"]}
                },
                "candidateGaps":{"$ref":"#/$defs/stringList"},
                "ambiguousRequirements":{"$ref":"#/$defs/stringList"},
                "evidenceReferences":{
                  "type":"array","minItems":1,"maxItems":60,
                  "items":{"type":"object","additionalProperties":false,
                    "properties":{
                      "source":{"type":"string","enum":["VACANCY","CANDIDATE_SKILL","CANDIDATE_LANGUAGE","CANDIDATE_EDUCATION","CANDIDATE_PROJECT","CANDIDATE_PROJECT_BULLET"]},
                      "sourceKey":{"type":"string","minLength":1,"maxLength":100},
                      "excerpt":{"type":"string","minLength":8,"maxLength":300}
                    },"required":["source","sourceKey","excerpt"]}
                },
                "confidenceScore":{"type":"integer","minimum":0,"maximum":100},
                "deterministicFallbackUsed":{"type":"boolean","enum":[false]}
              },
              "required":["roleSummary","mustHaveRequirements","preferredRequirements","responsibilities",
                "experienceRequirement","educationRequirement","languageRequirement","locationConstraints",
                "workAuthorizationSignals","candidateStrengths","candidateGaps","ambiguousRequirements",
                "evidenceReferences","confidenceScore","deterministicFallbackUsed"],
              "$defs":{
                "stringList":{"type":"array","maxItems":30,
                  "items":{"type":"string","minLength":1,"maxLength":500}}
              }
            }
            """;
    private final JsonNode schema;

    public JobAnalysisSchema(ObjectMapper objectMapper) {
        try {
            schema = objectMapper.readTree(SCHEMA);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Job analysis JSON Schema is invalid");
        }
    }

    public JsonNode value() {
        return schema.deepCopy();
    }
}
