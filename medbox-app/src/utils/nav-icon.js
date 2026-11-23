/**
 * 导航图标（`feat/mp-nav-drawer`）：底部栏 `MbTabBar` 与侧边栏 `MbDrawer` 共用。
 *
 * <p>图标用内联 SVG 转 data URI，免引第三方图标库；`name` 与 `config/tabbar.js`
 * 里的 `icon` 字段对齐。新增入口时在这里补一个同名图标即可。
 */

/** 24x24 描边风格图标主体（`stroke` 由 {@link iconDataUri} 统一注入）。 */
export const NAV_ICONS = {
  home: "<path d='M3 11l9-8 9 8'/><path d='M5 10v9a1 1 0 0 0 1 1h4v-5h4v5h4a1 1 0 0 0 1-1v-9'/>",
  device: "<rect x='4' y='4' width='16' height='16' rx='3'/><path d='M9 9h6M9 13h6'/>",
  medicine:
    "<rect x='3' y='9' width='18' height='6' rx='3' transform='rotate(45 12 12)'/><path d='M12 9v6M9 12h6'/>",
  plan: "<circle cx='12' cy='12' r='8'/><path d='M12 8v4l3 2'/>",
  record: "<rect x='5' y='3' width='14' height='18' rx='2'/><path d='M8 8h8M8 12h8M8 16h5'/>",
  ai: "<path d='M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z'/><circle cx='19' cy='5' r='1.6'/>",
  profile: "<circle cx='12' cy='8' r='4'/><path d='M4 21c0-4 4-6 8-6s8 2 8 6'/>",
  alarm: "<path d='M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9'/><path d='M13.7 21a2 2 0 0 1-3.4 0'/>",
  guardian:
    "<circle cx='9' cy='8' r='3.5'/><path d='M2.5 21c0-3.6 2.9-5.5 6.5-5.5s6.5 1.9 6.5 5.5'/><circle cx='17.5' cy='9' r='2.8'/><path d='M17 15.2c2.8.2 4.5 1.9 4.5 4.3'/>",
}

/**
 * 生成图标的 data URI。
 *
 * @param {string} name {@link NAV_ICONS} 的键
 * @param {boolean} [active] 是否选中态（选中用主色，否则灰色）
 * @returns {string} 可直接交给 `<image :src>` 的 data URI
 */
export function iconDataUri(name, active) {
  const color = active ? '#0e9f8e' : '#a8b4b6'
  const body = NAV_ICONS[name] || ''
  const svg =
    "<svg xmlns='http://www.w3.org/2000/svg' width='48' height='48' viewBox='0 0 24 24' fill='none' stroke='" +
    color +
    "' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" +
    body +
    '</svg>'
  return 'data:image/svg+xml,' + encodeURIComponent(svg)
}
