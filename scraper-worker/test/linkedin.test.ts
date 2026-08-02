import { strict as assert } from "node:assert";
import { test } from "node:test";
import { buildLinkedInSearchResult, isLinkedInSearchUrl } from "../src/linkedin.js";
import { buildResult } from "../src/parse.js";
import type { ExtractRequest, RawPageData } from "../src/types.js";

const request: ExtractRequest = {
  requestId: "linkedin-1",
  url: "https://www.linkedin.com/jobs/search/?keywords=java",
};

function page(overrides: Partial<RawPageData> = {}): RawPageData {
  return {
    finalUrl: request.url,
    jsonLdBlocks: [],
    meta: {},
    embeddedState: [],
    dom: {
      title: null,
      company: null,
      location: null,
      employmentType: null,
      description: null,
    },
    signals: {
      hasPasswordField: false,
      hasLoginForm: false,
      challengeMarker: false,
      accessDeniedTitle: false,
      bodyTextLength: 5000,
    },
    searchJobs: [],
    ...overrides,
  };
}

test("accepts only bounded public LinkedIn guest search routes", () => {
  assert.equal(isLinkedInSearchUrl("https://www.linkedin.com/jobs/search/?keywords=java"), true);
  assert.equal(
    isLinkedInSearchUrl(
      "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?start=25",
    ),
    true,
  );
  assert.equal(isLinkedInSearchUrl("http://www.linkedin.com/jobs/search"), false);
  assert.equal(isLinkedInSearchUrl("https://linkedin.com.evil.example/jobs/search"), false);
  assert.equal(isLinkedInSearchUrl("https://www.linkedin.com/jobs/view/1234567890"), false);
});

test("normalizes and deduplicates LinkedIn search cards", () => {
  const result = buildLinkedInSearchResult(
    request,
    page({
      searchJobs: [
        {
          title: " Software Engineering Intern ",
          company: " Example Company ",
          location: " Bucharest, Romania ",
          url: "/jobs/view/software-engineering-intern-1234567890?trackingId=secret",
        },
        {
          title: "Software Engineering Intern",
          company: "Example Company",
          location: "Bucharest",
          url: "https://ro.linkedin.com/jobs/view/1234567890?trk=duplicate",
        },
        {
          title: "Malicious",
          company: "Bad Company",
          location: "Remote",
          url: "https://linkedin.com.evil.example/jobs/view/9999999999",
        },
      ],
    }),
    50,
  );

  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.jobs.length, 1);
    assert.deepEqual(result.jobs[0], {
      externalId: "1234567890",
      title: "Software Engineering Intern",
      company: "Example Company",
      location: "Bucharest, Romania",
      url: "https://www.linkedin.com/jobs/view/1234567890",
    });
  }
});

test("LinkedIn detail candidates use the normal bounded vacancy contract", () => {
  const detailRequest: ExtractRequest = {
    requestId: "detail-1",
    url: "https://www.linkedin.com/jobs/view/1234567890",
  };
  const result = buildResult(
    detailRequest,
    page({
      finalUrl: detailRequest.url,
      dom: {
        title: "Graduate Software Engineer",
        company: "Example Company",
        location: "Bucharest, Romania",
        employmentType: "Full-time",
        description:
          "Join our graduate engineering programme and build tested services with an experienced mentoring team.",
      },
    }),
    50000,
  );

  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.title, "Graduate Software Engineer");
    assert.equal(result.evidence.descriptionSource, "DOM");
  }
});

test("search extraction stops on login and challenge pages", () => {
  assert.equal(
    buildLinkedInSearchResult(
      request,
      page({ signals: { ...page().signals, hasLoginForm: true } }),
      50,
    ).status,
    "AUTH_REQUIRED",
  );
  assert.equal(
    buildLinkedInSearchResult(
      request,
      page({ signals: { ...page().signals, challengeMarker: true } }),
      50,
    ).status,
    "CHALLENGE_DETECTED",
  );
});
