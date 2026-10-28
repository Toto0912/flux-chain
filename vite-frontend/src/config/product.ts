import { version } from '../../package.json';

export const PRODUCT_NAME = 'FluxChain';
export const PRODUCT_VERSION = version;
export const PRODUCT_URL = 'https://github.com/Toto0912/flux-chain';
export const ORIGINAL_PROJECT_URL = 'https://github.com/bqlpfy/flux-panel';

// 升级旧版本的默认名称，保留管理员自定义的网站名称。
export const resolveSiteName = (value: string | null): string => {
  if (!value || value === 'flux' || value === 'flux-panel') {
    return PRODUCT_NAME;
  }
  return value;
};
