// Manifest tipleri (TS) – şema ile hizalı

export interface ManifestRemote {
  name: string;
  entry: string;
  sri: string;
  versionRange: string;
  module: string;
  meta?: Record<string, unknown>;
}

export interface ManifestDictionary {
  locale: string;
  url: string;
  sri: string;
}

export interface ManifestPageRef {
  layoutUrl: string;
}

export interface Manifest {
  version: string;
  generatedAt?: string;
  remotes: ManifestRemote[];
  dictionaries?: ManifestDictionary[];
  meta?: Record<string, unknown>;
  pages?: Record<string, ManifestPageRef>;
}

export interface PageLayoutComponent {
  type: string;
  id: string;
  props?: Record<string, unknown>;
}

export interface PageLayoutManifest {
  version: string;
  id: string;
  layout: string;
  title: string;
  description?: string;
  components: PageLayoutComponent[];
  meta?: Record<string, unknown>;
}
