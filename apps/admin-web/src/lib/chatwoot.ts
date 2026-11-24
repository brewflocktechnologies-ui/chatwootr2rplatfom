// Chatwoot helpers. Pure functions so they can be unit tested.

export function normalizeChatwootBaseUrl(raw: string): string | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (url.username !== '' || url.password !== '') return null;
  return url.origin;
}

export function chatwootDashboardUrl(baseUrl: string, accountId: number): string | null {
  const origin = normalizeChatwootBaseUrl(baseUrl);
  if (origin === null || !Number.isInteger(accountId) || accountId <= 0) return null;
  return `${origin}/app/accounts/${accountId}/dashboard`;
}

export const CHATWOOT_SDK_SCRIPT_ID = 'chatwoot-website-sdk';

// Loads Chatwoot's native website widget the way the v4.20.1 snippet does
// (app/models/channel/web_widget.rb, web_widget_script): inject <origin>/packs/js/sdk.js,
// then call window.chatwootSDK.run({ websiteToken, baseUrl }) once the script has loaded.
export function loadChatwootWebsiteWidget(
  baseUrl: string,
  websiteToken: string,
  onError: (message: string) => void
): void {
  const origin = normalizeChatwootBaseUrl(baseUrl);
  if (origin === null) {
    onError('Chatwoot base URL is invalid.');
    return;
  }
  if (document.getElementById(CHATWOOT_SDK_SCRIPT_ID) !== null) return;

  const script = document.createElement('script');
  script.id = CHATWOOT_SDK_SCRIPT_ID;
  script.src = `${origin}/packs/js/sdk.js`;
  script.async = true;
  script.onload = () => {
    if (window.chatwootSDK === undefined) {
      onError('Chatwoot SDK loaded but did not expose chatwootSDK.');
      return;
    }
    window.chatwootSDK.run({ websiteToken, baseUrl: origin });
  };
  script.onerror = () => {
    onError('Chatwoot widget script could not be loaded. Is Chatwoot running?');
  };
  const firstScript = document.getElementsByTagName('script')[0];
  if (firstScript?.parentNode) {
    firstScript.parentNode.insertBefore(script, firstScript);
  } else {
    document.head.appendChild(script);
  }
}
