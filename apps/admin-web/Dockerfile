# syntax=docker/dockerfile:1
# Build context: repository root (needs the root pnpm workspace files). Build from the repo root:
#   docker build -f apps/admin-web/Dockerfile -t acme-admin-web:local .

ARG NODE_IMAGE=node:26.13.1-bookworm-slim
ARG PNPM_VERSION=12.23.0

FROM ${NODE_IMAGE} AS base
ARG PNPM_VERSION
ENV PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    NEXT_TELEMETRY_DISABLED=1
RUN npm install -g pnpm@${PNPM_VERSION} \
 && pnpm --version
WORKDIR /repo

# Dependencies only for the admin-web workspace graph.
FROM base AS deps
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/admin-web/package.json apps/admin-web/package.json
RUN pnpm install --frozen-lockfile --filter admin-web... --ignore-scripts

FROM base AS build
COPY --from=deps /repo/node_modules ./node_modules
COPY --from=deps /repo/apps/admin-web/node_modules ./apps/admin-web/node_modules
COPY apps/admin-web ./apps/admin-web
WORKDIR /repo/apps/admin-web
RUN pnpm exec next build

# Minimal runtime: standalone server only, no dev tooling, non-root user.
FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=4300 \
    HOSTNAME=0.0.0.0
WORKDIR /repo
COPY --from=build --chown=node:node /repo/apps/admin-web/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/admin-web/.next/static ./apps/admin-web/.next/static
COPY --from=build --chown=node:node /repo/apps/admin-web/public ./apps/admin-web/public
USER node
EXPOSE 4300
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 4300) + '/admin').then(r => process.exit(r.status < 500 ? 0 : 1)).catch(() => process.exit(1))"
CMD ["node", "apps/admin-web/server.js"]
