// Server-only runtime configuration. Only Route Handlers import this module.
// Nothing here may be prefixed NEXT_PUBLIC_ and nothing here is sent to the browser.

const DEFAULT_PLATFORM_API_URL = 'http://127.0.0.1:8080';

export interface ServerConfig {
  platformApiBaseUrl: URL;
  adminApiKey: string | null;
}

export class ConfigError extends Error {}

export function readServerConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const rawUrl = env.PLATFORM_API_URL?.trim() || DEFAULT_PLATFORM_API_URL;
  let platformApiBaseUrl: URL;
  try {
    platformApiBaseUrl = new URL(rawUrl);
  } catch {
    throw new ConfigError('PLATFORM_API_URL is not a valid URL');
  }
  if (platformApiBaseUrl.protocol !== 'http:' && platformApiBaseUrl.protocol !== 'https:') {
    throw new ConfigError('PLATFORM_API_URL must use http or https');
  }
  const key = env.PLATFORM_ADMIN_API_KEY?.trim() ?? '';
  return {
    platformApiBaseUrl,
    adminApiKey: key === '' ? null : key,
  };
}
