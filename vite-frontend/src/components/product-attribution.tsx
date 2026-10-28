import { ORIGINAL_PROJECT_URL, PRODUCT_NAME, PRODUCT_URL } from '@/config/product';

export const ProductAttribution = () => (
  <>
    Powered by <a href={PRODUCT_URL} target="_blank" rel="noopener noreferrer" className="hover:underline">{PRODUCT_NAME}</a> · 基于{' '}
    <a
      href={ORIGINAL_PROJECT_URL}
      target="_blank"
      rel="noopener noreferrer"
      className="hover:underline"
    >
      Flux Panel
    </a>
  </>
);
