package com.jobpilot.personalizedmatching;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("jobpilot.personalized-matching")
public record MatchingProperties(boolean enabled,@DefaultValue("25") int batchSize,@DefaultValue("100") int bootstrapBatchSize,
        @DefaultValue("1000") long tickMs,@DefaultValue("60") int leaseSeconds) {
    public MatchingProperties {
        if(batchSize<1||batchSize>100||bootstrapBatchSize<1||bootstrapBatchSize>500||tickMs<250||tickMs>60000||leaseSeconds<10||leaseSeconds>300)
            throw new IllegalArgumentException("Personalized matching bounds are invalid");
    }
}
