import path from 'node:path';
import type { NextConfig } from 'next';

// Standalone output for the Docker image. The tracing root is the workspace root so that
// the standalone server keeps the monorepo layout (server at apps/admin-web/server.js).
const nextConfig: NextConfig = {
  output: 'standalone',
  outputFileTracingRoot: path.resolve(process.cwd(), '..', '..'),
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;
