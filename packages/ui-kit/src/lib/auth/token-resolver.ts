export type TokenResolver = () => string | null;

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  try {
    const value = document.cookie
      .split('; ')
      .find((row) => row.startsWith(name + '='))
      ?.split('=')[1];
    return value ? decodeURIComponent(value) : null;
  } catch {
    return null;
  }
}

const readLocalStorageToken = (): string | null => {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    // Sık kullanılan anahtarları sırayla dene
    const keys = [
      'token',
      'access_token',
      'jwt',
      'id_token',
      'auth_token',
      'Authorization',
    ];
    for (const k of keys) {
      const val =
        window.localStorage.getItem(k) ??
        window.sessionStorage.getItem(k) ??
        readCookie(k);
      if (val && val.trim().length > 0) {
        // Authorization başlığında Bearer prefiksi varsa kes
        if (k.toLowerCase() === 'authorization' && /^bearer\s+/i.test(val)) {
          return val.replace(/^bearer\s+/i, '');
        }
        return val;
      }
    }
    return null;
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn('Token bilgisi okunamadı:', error);
    return null;
  }
};

const defaultResolver: TokenResolver = () => readLocalStorageToken();

let resolveToken: TokenResolver = defaultResolver;

export const getResolvedToken = (): string | null => {
  try {
    const token = resolveToken();
    return token ?? null;
  } catch (error) {
    // eslint-disable-next-line no-console
    console.warn('Token resolver çalıştırılamadı:', error);
    return null;
  }
};

export const registerTokenResolver = (resolver?: TokenResolver | null): void => {
  resolveToken = resolver ?? defaultResolver;
};

export const resetTokenResolver = (): void => {
  resolveToken = defaultResolver;
};

export const buildAuthHeaders = (): Record<string, string> => {
  const token = getResolvedToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
};
