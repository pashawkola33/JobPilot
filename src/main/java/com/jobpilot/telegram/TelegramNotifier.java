package com.jobpilot.telegram;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.matching.ScoreCard;
import java.util.List;

public interface TelegramNotifier {
    void notifyExcellent(Job job, ScoreCard score);
    void sendGoodMatchDigest(List<JobScore> scores);
}
