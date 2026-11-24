// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { checkWriteRequest, isWriteMethod, readAdminOriginConfig } from './request-guard';

const config = readAdminOriginConfig({});

function headers(init: Record<string, string>): Headers {
  return new Headers({ host: 'localhost:4300', ...init });
}

describe('checkWriteRequest', () => {
  it('rejects a cross-site POST', () => {
    const result = checkWriteRequest(
      headers({ origin: 'https://evil.example', 'sec-fetch-site': 'cross-site' }),
      config
    );
    expect(result).toEqual({ status: 403, error: 'forbidden_origin' });
  });

  it('rejects a POST whose Origin is foreign even without Sec-Fetch-Site', () => {
    expect(checkWriteRequest(headers({ origin: 'http://evil.example:4300' }), config)).toEqual({
      status: 403,
      error: 'forbidden_origin',
    });
  });

  it('allows a same-origin POST', () => {
    const result = checkWriteRequest(
      headers({ origin: 'http://localhost:4300', 'sec-fetch-site': 'same-origin' }),
      config
    );
    expect(result).toBeNull();
  });

  it('allows a POST with no Origin when Sec-Fetch-Site is none', () => {
    expect(checkWriteRequest(headers({ 'sec-fetch-site': 'none' }), config)).toBeNull();
  });

  it('rejects a POST with no Origin and no Sec-Fetch-Site', () => {
    expect(checkWriteRequest(headers({}), config)).toEqual({ status: 403, error: 'forbidden_origin' });
  });

  it('rejects a write whose Host is not the admin host', () => {
    expect(
      checkWriteRequest(new Headers({ host: 'evil.example', origin: 'http://localhost:4300' }), config)
    ).toEqual({ status: 403, error: 'forbidden_host' });
  });

  it('accepts the configured ADMIN_WEB_ORIGIN as origin and host', () => {
    const custom = readAdminOriginConfig({ ADMIN_WEB_ORIGIN: 'http://127.0.0.1:4310/' });
    expect(
      checkWriteRequest(
        new Headers({ host: '127.0.0.1:4310', origin: 'http://127.0.0.1:4310', 'sec-fetch-site': 'same-origin' }),
        custom
      )
    ).toBeNull();
  });

  it('only treats POST, PUT, PATCH and DELETE as writes', () => {
    expect(isWriteMethod('POST')).toBe(true);
    expect(isWriteMethod('DELETE')).toBe(true);
    expect(isWriteMethod('GET')).toBe(false);
  });
});
