# Build stage: install all dependencies and compile TypeScript to dist/.
FROM node:22.11.0-bookworm-slim AS build
WORKDIR /build
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 PUPPETEER_SKIP_DOWNLOAD=1
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci --no-audit --no-fund
COPY src ./src
RUN npm run build

# Runtime stage: production dependencies + a prepared CloakBrowser Chromium.
FROM node:22.11.0-bookworm-slim AS runtime
ENV NODE_ENV=production \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PUPPETEER_SKIP_DOWNLOAD=1 \
    HOME=/home/worker

# Chromium runtime shared libraries and root certificates (for the one-time
# binary download during the image build).
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates fonts-liberation libnss3 libnspr4 libatk1.0-0 \
      libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 \
      libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
      libasound2 libatspi2.0-0 libx11-6 libxcb1 libxext6 procps \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd -g 10001 worker \
  && useradd -m -u 10001 -g 10001 -d /home/worker worker

WORKDIR /app
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

# Prepare the stealth Chromium binary at build time so no download ever happens
# on a request path. It is cached in the runtime user's home and resolved via
# CloakBrowser's binaryInfo() at startup.
RUN chown -R worker:worker /app /home/worker
USER worker
RUN node node_modules/cloakbrowser/dist/cli.js install
COPY --from=build --chown=worker:worker /build/dist ./dist

EXPOSE 3000
HEALTHCHECK --interval=20s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||3000)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
ENTRYPOINT ["node", "dist/src/index.js"]
