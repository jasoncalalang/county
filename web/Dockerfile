# Build context: the repository root.  docker build -f web/Dockerfile .
# One image serves every environment: it carries no environment-specific value (ADR 0010).
FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS build
ENV NEXT_TELEMETRY_DISABLED=1
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# No COUNTY_DEV_API_ORIGIN here, so no /api rewrite is baked into the build.
RUN npm run build && mkdir -p public

FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
WORKDIR /app
COPY --from=build --chown=1000:1000 /src/web/.next/standalone ./
COPY --from=build --chown=1000:1000 /src/web/.next/static ./.next/static
COPY --from=build --chown=1000:1000 /src/web/public ./public
EXPOSE 3000
# The node user of the base image. Only /app/.next/cache needs to be writable (an emptyDir).
USER 1000
CMD ["node", "server.js"]
