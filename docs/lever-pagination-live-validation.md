# Lever pagination live validation

Lever pagination was validated in production on 2026-08-10.

## Release

- PR: #12 `fix(lever): paginate public postings API`
- Production commit: `b7ded74e84c7f9f5b5ee82d295de31a8db414aea`
- Validation run: `13f3004a-5bba-4a20-94b7-f95f69a0b420`

## Veeva result

Before this change, `lever/veeva` was the only configured tenant still failing
with `RESPONSE_TOO_LARGE`.

The production validation result was:

- provider: `lever`
- tenant: `veeva`
- status: `SUCCESS`
- failure category: `NONE`
- fetched: `824`
- duration: `5688 ms`

The shared 10 MiB HTTP response limit was not increased.

## Full run

The controlled ingestion cycle completed with:

- 56 tenant attempts
- 56 successes
- 0 failures
- 6805 fetched vacancies
- 6805 unique raw vacancies
- 0 duplicate raw vacancies
- 3 MATCH
- 104 REVIEW
- 6698 REJECT

The disposition totals reconcile exactly:

`3 + 104 + 6698 = 6805`

After validation, the normal schedule `0 0 */6 * * *` was restored.
Production remained healthy with one app instance on the same build.

## Conclusion

The `lever/veeva RESPONSE_TOO_LARGE` issue is closed. Lever pagination is
production-validated.
