/**
 * Fixed-category counters. Labels are a closed enum only — never a URL,
 * hostname, company, title, or exception message.
 */

export const METRIC_CATEGORIES = [
  "requested",
  "extracted",
  "insufficient",
  "blocked",
  "challenge",
  "timeout",
  "invalid_response",
  "unavailable",
  "overloaded",
] as const;

export type MetricCategory = (typeof METRIC_CATEGORIES)[number];

export class Metrics {
  private readonly counters = new Map<MetricCategory, number>(
    METRIC_CATEGORIES.map((category) => [category, 0]),
  );

  increment(category: MetricCategory): void {
    this.counters.set(category, (this.counters.get(category) ?? 0) + 1);
  }

  snapshot(): Record<MetricCategory, number> {
    const result = {} as Record<MetricCategory, number>;
    for (const category of METRIC_CATEGORIES) result[category] = this.counters.get(category) ?? 0;
    return result;
  }
}
