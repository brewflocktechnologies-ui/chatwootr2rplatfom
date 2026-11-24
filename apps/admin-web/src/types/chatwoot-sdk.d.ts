// Surface of Chatwoot's website SDK used by the demo page (v4.20.1, app/javascript/entrypoints/sdk.js).
export {};

declare global {
  interface Window {
    chatwootSDK?: {
      run: (options: { websiteToken: string; baseUrl: string }) => void;
    };
  }
}
