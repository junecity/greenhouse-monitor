export type PortalConfig = {
  apiUrl: string;
  authority: string;
  hostedUiBaseUrl: string;
  clientId: string;
  redirectUri: string;
  logoutUri: string;
};

export function portalConfig(): PortalConfig | null {
  const config = {
    apiUrl: process.env.NEXT_PUBLIC_ADMIN_API_URL || "",
    authority: process.env.NEXT_PUBLIC_COGNITO_AUTHORITY || "",
    hostedUiBaseUrl: process.env.NEXT_PUBLIC_COGNITO_HOSTED_UI_BASE_URL || "",
    clientId: process.env.NEXT_PUBLIC_COGNITO_CLIENT_ID || "",
    redirectUri: process.env.NEXT_PUBLIC_COGNITO_REDIRECT_URI || "",
    logoutUri: process.env.NEXT_PUBLIC_COGNITO_LOGOUT_URI || "",
  };
  return Object.values(config).every(Boolean) ? config : null;
}
