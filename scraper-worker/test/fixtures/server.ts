import { createServer, type Server } from "node:http";
import { once } from "node:events";
import type { AddressInfo } from "node:net";

export interface Fixture {
  origin: string;
  imageRequests: number;
  close: () => Promise<void>;
}

const DESCRIPTION =
  "Build and maintain Java backend services with Spring Boot, PostgreSQL, and REST APIs, " +
  "collaborating with a mentoring engineering team on tested, reviewed production code.";

export const providerDetailFixtures = [
  {
    provider: "GREENHOUSE",
    url: "https://job-boards.greenhouse.io/fixture/jobs/1234567",
    html: `<!doctype html><html><head><title>Graduate Engineer</title></head><body>
      <h1 class="job__title">Graduate Engineer</h1>
      <div class="company-name">Fixture Greenhouse Company</div>
      <div class="job__location">Bucharest, Romania</div>
      <section class="job__description">${DESCRIPTION}</section>
      <a href="#app">Apply</a></body></html>`,
  },
  {
    provider: "ASHBY",
    url: "https://jobs.ashbyhq.com/fixture/11111111-2222-4333-8444-555555555555",
    html: `<!doctype html><html><head><title>Associate Engineer @ Fixture Ashby Company</title></head><body>
      <main><h1 data-testid="job-title">Associate Engineer</h1>
      <div data-testid="company-name">Fixture Ashby Company</div>
      <div data-testid="job-location">Bucharest</div>
      <div data-testid="job-employment-type">Full time</div>
      <div data-testid="job-location-type">Hybrid</div>
      <section data-testid="job-description">${DESCRIPTION}</section>
      <a href="/fixture/11111111-2222-4333-8444-555555555555/application">Apply</a></main>
      <script type="application/json">${JSON.stringify({
        job: {
          id: "11111111-2222-4333-8444-555555555555",
          title: "Associate Engineer",
          jobUrl: "https://jobs.ashbyhq.com/fixture/11111111-2222-4333-8444-555555555555",
        },
      })}</script></body></html>`,
  },
  {
    provider: "LEVER",
    url: "https://jobs.lever.co/fixture/3b94218d-3a5a-4dd1-91c7-5f18655c93a8",
    html: `<!doctype html><html><head><title>Software Engineer I</title></head><body>
      <main class="posting-page"><div class="posting-headline"><h2>Software Engineer I</h2></div>
      <div class="posting-categories"><span class="location">Bucharest</span>
      <span class="commitment">Full-time</span><span class="workplaceTypes">On-site</span></div>
      <div class="content">${DESCRIPTION}</div>
      <a class="postings-btn" href="/fixture/3b94218d-3a5a-4dd1-91c7-5f18655c93a8/apply">Apply</a></main>
      </body></html>`,
  },
  {
    provider: "RECRUITEE",
    url: "https://fixture.recruitee.com/o/junior-engineer",
    html: `<!doctype html><html><head><title>Junior Engineer</title></head><body>
      <main><h1 data-ui="job-title">Junior Engineer</h1>
      <div data-ui="company-name">Fixture Recruitee Company</div>
      <div data-ui="job-location">Bucharest, Romania</div>
      <div data-ui="employment-type">Full time</div><div data-ui="workplace-type">Hybrid</div>
      <section data-ui="job-description">${DESCRIPTION}</section>
      <a href="/o/junior-engineer/c/new">Apply</a></main></body></html>`,
  },
] as const;

/** A synthetic local vacancy site. No external network is ever contacted. */
export async function startFixture(): Promise<Fixture> {
  const state = { imageRequests: 0 };
  const server: Server = createServer((req, res) => {
    const path = (req.url ?? "/").split("?")[0] ?? "/";
    if (path === "/pixel.png") {
      state.imageRequests += 1;
      res.writeHead(200, { "content-type": "image/png" });
      res.end(Buffer.from([137, 80, 78, 71]));
      return;
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "set-cookie": "sid=abc; Path=/" });
    res.end(pages[path] ?? "<!doctype html><html><body><h1>Nothing here</h1></body></html>");
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = (server.address() as AddressInfo).port;
  return {
    origin: `http://127.0.0.1:${port}`,
    get imageRequests() {
      return state.imageRequests;
    },
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  } as Fixture;
}

const jobPostingJson = JSON.stringify({
  "@context": "https://schema.org",
  "@type": "JobPosting",
  title: "Java Backend Intern",
  hiringOrganization: { "@type": "Organization", name: "Fixture Company" },
  jobLocation: { address: { addressLocality: "Bucharest", addressCountry: "Romania" } },
  employmentType: "INTERN",
  datePosted: "2026-07-19",
  description: DESCRIPTION,
});

const pages: Record<string, string> = {
  // JSON-LD injected by JavaScript after initial load.
  "/jsonld": `<!doctype html><html><head><title>Loading</title></head><body>
    <h1>Loading...</h1>
    <script>
      setTimeout(function () {
        var s = document.createElement('script');
        s.type = 'application/ld+json';
        s.textContent = ${JSON.stringify(jobPostingJson)};
        document.head.appendChild(s);
      }, 50);
    </script></body></html>`,
  // A vacancy assembled entirely in the DOM by JavaScript.
  "/dom": `<!doctype html><html><head><title>Loading</title></head><body>
    <div id="root"></div>
    <script>
      setTimeout(function () {
        document.getElementById('root').innerHTML =
          '<h1>Backend Developer Intern</h1>' +
          '<div class="company">Fixture DOM Company</div>' +
          '<div class="job-description">${DESCRIPTION}</div>';
      }, 50);
    </script></body></html>`,
  "/linkedin-detail": `<!doctype html><html><head><title>Graduate Software Engineer</title></head>
    <body><main>
      <h1 class="top-card-layout__title">Graduate Software Engineer</h1>
      <a class="topcard__org-name-link">Fixture LinkedIn Company</a>
      <span class="topcard__flavor--bullet">Bucharest, Romania</span>
      <div class="show-more-less-html__markup">${DESCRIPTION}</div>
    </main></body></html>`,
  "/linkedin-search": `<!doctype html><html><head><title>Jobs</title></head><body>
    <ul><li class="base-card job-search-card">
      <h3 class="base-search-card__title">Software Engineering Intern</h3>
      <h4 class="base-search-card__subtitle">Fixture LinkedIn Company</h4>
      <span class="job-search-card__location">Bucharest, Romania</span>
      <a class="base-card__full-link"
         href="https://www.linkedin.com/jobs/view/software-engineering-intern-1234567890?trk=x">View</a>
    </li></ul></body></html>`,
  // A login wall.
  "/login": `<!doctype html><html><head><title>Sign in</title></head><body>
    <form action="/login"><input type="password" name="pw"></form></body></html>`,
  // A page that pulls in an image which interception must block.
  "/withimage": `<!doctype html><html><head>
    <script type="application/ld+json">${jobPostingJson}</script></head>
    <body><h1>Java Backend Intern</h1><img src="/pixel.png" alt="x"></body></html>`,
};
