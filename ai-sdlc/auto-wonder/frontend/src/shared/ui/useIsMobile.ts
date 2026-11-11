import { useEffect, useState } from 'react';

const MOBILE_QUERY = '(max-width: 767.98px)';

function canMatchMedia() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function';
}

function readIsMobile(): boolean {
  return canMatchMedia() ? window.matchMedia(MOBILE_QUERY).matches : false;
}

/** 768px 以下的统一移动端判断：媒体查询变化时自动更新，无 matchMedia 环境按桌面处理。 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(readIsMobile);

  useEffect(() => {
    if (!canMatchMedia()) return;
    const media = window.matchMedia(MOBILE_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setIsMobile(event.matches);
    setIsMobile(media.matches);
    media.addEventListener('change', handleChange);
    return () => media.removeEventListener('change', handleChange);
  }, []);

  return isMobile;
}
