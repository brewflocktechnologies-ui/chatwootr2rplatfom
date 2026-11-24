import { describe, expect, it } from 'vitest';
import { chatwootDashboardUrl, normalizeChatwootBaseUrl } from './chatwoot';

describe('normalizeChatwootBaseUrl', () => {
  it('keeps only the origin for http and https URLs', () => {
    expect(normalizeChatwootBaseUrl('http://localhost:3100/')).toBe('http://localhost:3100');
    expect(normalizeChatwootBaseUrl('https://chat.example.com/app')).toBe('https://chat.example.com');
  });

  it('rejects other schemes, embedded credentials and garbage', () => {
    expect(normalizeChatwootBaseUrl('javascript:alert(1)')).toBeNull();
    expect(normalizeChatwootBaseUrl('ftp://example.com')).toBeNull();
    expect(normalizeChatwootBaseUrl('http://user:pass@localhost:3100')).toBeNull();
    expect(normalizeChatwootBaseUrl('not a url')).toBeNull();
  });
});

describe('chatwootDashboardUrl', () => {
  it('builds the account dashboard link from the platform base URL', () => {
    expect(chatwootDashboardUrl('http://localhost:3100', 4)).toBe('http://localhost:3100/app/accounts/4/dashboard');
  });

  it('returns null for an invalid account id or base URL', () => {
    expect(chatwootDashboardUrl('http://localhost:3100', 0)).toBeNull();
    expect(chatwootDashboardUrl('http://localhost:3100', 1.5)).toBeNull();
    expect(chatwootDashboardUrl('bad', 4)).toBeNull();
  });
});
