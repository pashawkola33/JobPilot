import { strict as assert } from "node:assert";
import { test } from "node:test";
import { buildResult, htmlToText } from "../src/parse.js";
import type { ExtractRequest, RawPageData } from "../src/types.js";

const request: ExtractRequest = { requestId: "req-1", url: "https://93.184.216.34/jobs/9" };
const DESCRIPTION =
  "Build and maintain Java backend services with Spring Boot, PostgreSQL, and REST APIs. " +
  "You will collaborate with a mentoring engineering team and write tested production code.";

function page(overrides: Partial<RawPageData>): RawPageData {
  return {
    finalUrl: "https://93.184.216.34/jobs/9",
    jsonLdBlocks: [],
    meta: {},
    embeddedState: [],
    dom: { title: null, company: null, location: null, employmentType: null, description: null },
    signals: {
      hasPasswordField: false,
      hasLoginForm: false,
      challengeMarker: false,
      accessDeniedTitle: false,
      bodyTextLength: 5000,
    },
    ...overrides,
  };
}

function jobPosting(extra = ""): string {
  return JSON.stringify({
    "@context": "https://schema.org",
    "@type": "JobPosting",
    title: "Java Backend Intern",
    hiringOrganization: { "@type": "Organization", name: "Example Company" },
    jobLocation: { address: { addressLocality: "Bucharest", addressCountry: "Romania" } },
    employmentType: "INTERN",
    datePosted: "2026-07-19",
    description: DESCRIPTION + extra,
    url: "https://93.184.216.34/jobs/9",
  });
}

test("extracts a single JSON-LD JobPosting object", () => {
  const result = buildResult(request, page({ jsonLdBlocks: [jobPosting()] }), 50000);
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.title, "Java Backend Intern");
    assert.equal(result.job.company, "Example Company");
    assert.ok(result.job.description?.includes("Spring Boot"));
    assert.equal(result.job.externalId, "9");
    assert.equal(result.job.sourceUrl, "https://93.184.216.34/jobs/9");
    assert.equal(result.job.employmentType, "INTERN");
    assert.equal(result.job.publishedAt, "2026-07-19");
    assert.equal(result.evidence.titleSource, "JSON_LD");
    assert.equal(result.evidence.descriptionSource, "JSON_LD");
    assert.ok(result.job.location?.includes("Bucharest"));
  }
});

test("extracts a JSON-LD array", () => {
  const block = `[${JSON.stringify({ "@type": "Organization", name: "Other" })},${jobPosting()}]`;
  assert.equal(buildResult(request, page({ jsonLdBlocks: [block] }), 50000).status, "EXTRACTED");
});

test("extracts a JSON-LD @graph", () => {
  const block = JSON.stringify({
    "@context": "https://schema.org",
    "@graph": [{ "@type": "WebPage" }, JSON.parse(jobPosting())],
  });
  assert.equal(buildResult(request, page({ jsonLdBlocks: [block] }), 50000).status, "EXTRACTED");
});

test("selects the JobPosting across multiple script blocks", () => {
  const blocks = [JSON.stringify({ "@type": "Organization", name: "X" }), jobPosting()];
  assert.equal(buildResult(request, page({ jsonLdBlocks: blocks }), 50000).status, "EXTRACTED");
});

test("malformed JSON-LD does not throw and falls through", () => {
  const result = buildResult(request, page({ jsonLdBlocks: ['{"@type":"JobPosting", bad}'] }), 50000);
  assert.equal(result.status, "UNSUPPORTED");
});

test("extracts a JavaScript-injected DOM vacancy", () => {
  const result = buildResult(
    request,
    page({
      dom: {
        title: "Backend Developer Intern",
        company: "DOM Company",
        location: "Remote",
        employmentType: "Internship",
        description: DESCRIPTION,
      },
    }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.evidence.titleSource, "DOM");
    assert.equal(result.evidence.descriptionSource, "DOM");
  }
});

test("metadata can supply the required title while description remains optional", () => {
  const result = buildResult(
    request,
    page({ meta: { "og:title": "Some Role", "og:site_name": "Some Co" } }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.externalId, "9");
    assert.equal(result.job.description, null);
    assert.equal(result.evidence.titleSource, "META");
  }
});

test("classifies login, challenge, and access-denied pages conservatively", () => {
  assert.equal(
    buildResult(request, page({ signals: { ...page({}).signals, hasPasswordField: true } }), 50000).status,
    "AUTH_REQUIRED",
  );
  assert.equal(
    buildResult(request, page({ signals: { ...page({}).signals, challengeMarker: true } }), 50000).status,
    "CHALLENGE_DETECTED",
  );
  assert.equal(
    buildResult(request, page({ signals: { ...page({}).signals, accessDeniedTitle: true } }), 50000).status,
    "BLOCKED",
  );
});

test("rejects NUL / control characters in fields", () => {
  const block = JSON.stringify({
    "@type": "JobPosting",
    title: "Java\u0000Intern",
    hiringOrganization: { name: "Example Company" },
    description: DESCRIPTION,
    url: "https://93.184.216.34/jobs/9",
  });
  // Title is rejected as the only title source -> insufficient.
  assert.equal(buildResult(request, page({ jsonLdBlocks: [block] }), 50000).status, "INSUFFICIENT_DATA");
});

test("does not map a too-short optional description", () => {
  const block = JSON.stringify({
    "@type": "JobPosting",
    title: "Java Backend Intern",
    hiringOrganization: { name: "Example Company" },
    description: "too short",
    url: "https://93.184.216.34/jobs/9",
  });
  const result = buildResult(request, page({ jsonLdBlocks: [block] }), 50000);
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.equal(result.job.description, null);
});

test("truncates an oversized description to the configured maximum", () => {
  const long = DESCRIPTION + " ".repeat(0) + "x".repeat(5000);
  const block = JSON.stringify({
    "@type": "JobPosting",
    title: "Java Backend Intern",
    hiringOrganization: { name: "Example Company" },
    description: long,
    url: "https://93.184.216.34/jobs/9",
  });
  const result = buildResult(request, page({ jsonLdBlocks: [block] }), 200);
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.ok((result.job.description?.length ?? 0) <= 200);
});

test("ignores a cross-origin canonical URL and uses the validated final URL", () => {
  const block = JSON.stringify({
    "@type": "JobPosting",
    title: "Java Backend Intern",
    hiringOrganization: { name: "Example Company" },
    description: DESCRIPTION,
    url: "https://evil.example/elsewhere",
  });
  const result = buildResult(request, page({ jsonLdBlocks: [block] }), 50000);
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.ok(result.job.canonicalUrl.startsWith("https://93.184.216.34/"));
  }
});

test("a generic non-vacancy page is unsupported", () => {
  const result = buildResult(request, page({ signals: { ...page({}).signals, bodyTextLength: 100 } }), 50000);
  assert.equal(result.status, "UNSUPPORTED");
});

test("extracts a Greenhouse detail JobPosting with a stable provider ID", () => {
  const greenhouseRequest: ExtractRequest = {
    requestId: "greenhouse-jsonld",
    url: "https://job-boards.greenhouse.io/example/jobs/1234567",
  };
  const block = JSON.stringify({
    "@context": "https://schema.org",
    "@type": "JobPosting",
    identifier: { "@type": "PropertyValue", value: "1234567" },
    title: "Graduate Backend Engineer",
    hiringOrganization: { name: "Example Greenhouse Company" },
    description: `<p>${DESCRIPTION}</p>`,
    employmentType: "FULL_TIME",
    jobLocationType: "TELECOMMUTE",
    datePosted: "2026-07-20",
    url: greenhouseRequest.url,
  });
  const result = buildResult(
    greenhouseRequest,
    page({ finalUrl: greenhouseRequest.url, jsonLdBlocks: [block] }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.externalId, "1234567");
    assert.equal(result.job.provider, "GREENHOUSE");
    assert.equal(result.job.workplaceType, "TELECOMMUTE");
  }
});

test("extracts a Greenhouse detail page through provider DOM fallback", () => {
  const url = "https://job-boards.greenhouse.io/example/jobs/7654321";
  const result = buildResult(
    { requestId: "greenhouse-dom", url },
    page({
      finalUrl: url,
      dom: {
        externalId: "7654321",
        provider: "GREENHOUSE",
        title: "Software Engineering Intern",
        company: "Example Greenhouse Company",
        location: "Bucharest, Romania",
        employmentType: "Internship",
        workplaceType: "Hybrid",
        publishedAt: "2026-07-20",
        applicationUrl: `${url}#app`,
        description: DESCRIPTION,
      },
    }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.externalId, "7654321");
    assert.equal(result.job.applicationUrl, `${url}#app`);
    assert.equal(result.evidence.titleSource, "DOM");
  }
});

test("extracts Ashby embedded job state", () => {
  const url = "https://jobs.ashbyhq.com/example/11111111-2222-4333-8444-555555555555";
  const embedded = JSON.stringify({
    props: {
      job: {
        id: "11111111-2222-4333-8444-555555555555",
        title: "Associate Software Engineer",
        companyName: "Example Ashby Company",
        location: "Bucharest",
        employmentType: "Full time",
        workplaceType: "Hybrid",
        descriptionHtml: `<div>${DESCRIPTION}</div>`,
        publishedAt: "2026-07-20T10:00:00Z",
        jobUrl: url,
        applicationUrl: `${url}/application`,
      },
    },
  });
  const result = buildResult(
    { requestId: "ashby", url },
    page({ finalUrl: url, embeddedState: [embedded] }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") {
    assert.equal(result.job.provider, "ASHBY");
    assert.equal(result.evidence.titleSource, "EMBEDDED");
    assert.equal(result.job.company, "Example Ashby Company");
  }
});

test("extracts a Lever public detail page", () => {
  const id = "3b94218d-3a5a-4dd1-91c7-5f18655c93a8";
  const url = `https://jobs.lever.co/example/${id}`;
  const result = buildResult(
    { requestId: "lever", url },
    page({
      finalUrl: url,
      dom: {
        externalId: id,
        provider: "LEVER",
        title: "Software Engineer I",
        company: "Example Lever Company",
        location: "Bucharest",
        employmentType: "Full-time",
        workplaceType: "On-site",
        applicationUrl: `${url}/apply`,
        publishedAt: null,
        description: DESCRIPTION,
      },
    }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.equal(result.job.provider, "LEVER");
});

test("extracts a Recruitee public detail page", () => {
  const url = "https://example.recruitee.com/o/junior-software-engineer";
  const result = buildResult(
    { requestId: "recruitee", url },
    page({
      finalUrl: url,
      dom: {
        externalId: "junior-software-engineer",
        provider: "RECRUITEE",
        title: "Junior Software Engineer",
        company: "Example Recruitee Company",
        location: "Bucharest, Romania",
        employmentType: "Full time",
        workplaceType: "Hybrid",
        applicationUrl: `${url}/c/new`,
        publishedAt: "2026-07-20",
        description: DESCRIPTION,
      },
    }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.equal(result.job.externalId, "junior-software-engineer");
});

test("missing required title is insufficient", () => {
  const url = "https://jobs.lever.co/example/3b94218d-3a5a-4dd1-91c7-5f18655c93a8";
  const result = buildResult(
    { requestId: "missing-title", url },
    page({ finalUrl: url, dom: { ...page({}).dom, provider: "LEVER", externalId: "3b94218d-3a5a-4dd1-91c7-5f18655c93a8" } }),
    50000,
  );
  assert.equal(result.status, "INSUFFICIENT_DATA");
});

test("missing required external ID is insufficient", () => {
  const url = "https://job-boards.greenhouse.io/example/not-a-detail";
  const result = buildResult(
    { requestId: "missing-id", url },
    page({ finalUrl: url, dom: { ...page({}).dom, provider: "GREENHOUSE", title: "Graduate Engineer" } }),
    50000,
  );
  assert.equal(result.status, "INSUFFICIENT_DATA");
});

test("invalid application URL is omitted without invalidating the job", () => {
  const url = "https://example.recruitee.com/o/graduate-engineer";
  const result = buildResult(
    { requestId: "bad-application", url },
    page({
      finalUrl: url,
      dom: {
        ...page({}).dom,
        provider: "RECRUITEE",
        externalId: "graduate-engineer",
        title: "Graduate Engineer",
        applicationUrl: "http://127.0.0.1/apply",
      },
    }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.equal(result.job.applicationUrl, null);
});

test("selects the matching JobPosting when multiple objects are present", () => {
  const url = "https://job-boards.greenhouse.io/example/jobs/222";
  const block = JSON.stringify([
    {
      "@type": "JobPosting",
      identifier: { value: "111" },
      title: "Wrong Role",
      url: "https://job-boards.greenhouse.io/example/jobs/111",
    },
    {
      "@type": "JobPosting",
      identifier: { value: "222" },
      title: "Matching Graduate Role",
      url,
    },
  ]);
  const result = buildResult(
    { requestId: "multiple", url },
    page({ finalUrl: url, jsonLdBlocks: [block] }),
    50000,
  );
  assert.equal(result.status, "EXTRACTED");
  if (result.status === "EXTRACTED") assert.equal(result.job.title, "Matching Graduate Role");
});

test("enforces required field length limits", () => {
  const url = "https://job-boards.greenhouse.io/example/not-a-detail";
  const block = JSON.stringify({
    "@type": "JobPosting",
    identifier: { value: "x".repeat(201) },
    title: "T".repeat(301),
    url,
  });
  assert.equal(
    buildResult({ requestId: "bounds", url }, page({ finalUrl: url, jsonLdBlocks: [block] }), 50000).status,
    "INSUFFICIENT_DATA",
  );
});

test("safe HTML-to-text conversion removes active markup and decodes entities", () => {
  const text = htmlToText(
    "&lt;script&gt;alert(1)&lt;/script&gt;<p>Build &amp; test &#x1F680;</p><style>bad</style><br>Safely",
  );
  assert.ok(!text.includes("alert(1)"));
  assert.ok(!text.includes("bad"));
  assert.ok(text.includes("Build & test 🚀"));
  assert.ok(text.includes("Safely"));
});

test("known closed Greenhouse and missing Ashby shapes are unsupported", () => {
  const greenhouseUrl = "https://job-boards.greenhouse.io/example?error=true";
  assert.equal(
    buildResult(
      { requestId: "closed-greenhouse", url: greenhouseUrl },
      page({ finalUrl: greenhouseUrl, dom: { ...page({}).dom, provider: "GREENHOUSE", title: "Current openings at Example" } }),
      50000,
    ).status,
    "UNSUPPORTED",
  );
  const ashbyUrl = "https://jobs.ashbyhq.com/example/11111111-2222-4333-8444-555555555555";
  assert.equal(
    buildResult(
      { requestId: "missing-ashby", url: ashbyUrl },
      page({ finalUrl: ashbyUrl, dom: { ...page({}).dom, provider: "ASHBY", title: "Job not found" } }),
      50000,
    ).status,
    "UNSUPPORTED",
  );
});
