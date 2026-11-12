# Builds this repository from source and serves the result.
#
#   docker build -t hyperopen-vaults --build-arg HYPEROPEN_BUILD_ID="$(git rev-parse HEAD)" .
#   docker run --rm -p 8080:8080 hyperopen-vaults
#
# The image holds the static release and a Caddy server that sends the same
# security headers as the Cloudflare Pages deployment. See
# docs/self-hosting.md.

FROM node:22-trixie-slim AS build

# shadow-cljs needs a JVM, and its compiler needs Java 21. git is not
# needed: the build id comes from the build argument.
RUN apt-get update \
  && apt-get install -y --no-install-recommends openjdk-21-jre-headless ca-certificates \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts

COPY . .

# The commit this image is built from. The footer links to the source at
# this commit.
ARG HYPEROPEN_BUILD_ID=""
ARG HYPEROPEN_BUILD_BRANCH=""
ENV HYPEROPEN_BUILD_ID=${HYPEROPEN_BUILD_ID} \
    HYPEROPEN_BUILD_BRANCH=${HYPEROPEN_BUILD_BRANCH} \
    HYPEROPEN_BUILD_ENV=prod

RUN npm run build

FROM caddy:2-alpine

COPY --from=build /app/out/release-server/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/out/release-public /srv

# Not root. Caddy keeps its state (certificates, when SITE_ADDRESS is a
# domain) under /data and /config.
RUN chown -R 65534:65534 /data /config
USER 65534:65534

ENV SITE_ADDRESS=":8080"
EXPOSE 8080
