/**
 * light-styles.js — 玻璃样式（注入全局，.lg- 前缀）
 * 视觉层放在 light DOM（非 shadow），因为 Chrome 中 shadow DOM 内
 * backdrop-filter 会被祖先 transform 抑制，而 light DOM 不会。
 * 通过 <style> 注入 document.head，仅注入一次。
 */
export const GLASS_STYLES = `
  .lg-shell {
    position: relative;
    display: inline-flex;
  }
  .lg-glass {
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: 24px;
    padding: 24px 32px;
    border-radius: 999px;
    overflow: hidden;
    transition: all 0.2s ease-in-out;
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
  }
  .lg-glass--light {
    box-shadow: 0 16px 70px rgba(0, 0, 0, 0.75);
  }
  .lg-warp {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    pointer-events: none;
  }
  .lg-content {
    position: relative;
    z-index: 1;
    font: 500 20px/1 system-ui, -apple-system, sans-serif;
    color: #fff;
    text-shadow: 0 2px 12px rgba(0, 0, 0, 0.4);
    transition: all 0.15s ease-in-out;
  }
  .lg-glass--light .lg-content {
    text-shadow: 0 2px 12px rgba(0, 0, 0, 0);
  }
  .lg-border,
  .lg-border--overlay {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    padding: 1.5px;
    pointer-events: none;
    box-shadow:
      0 0 0 0.5px rgba(255, 255, 255, 0.5) inset,
      0 1px 3px rgba(255, 255, 255, 0.25) inset,
      0 1px 4px rgba(0, 0, 0, 0.35);
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    -webkit-mask-composite: xor;
    mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
    mask-composite: exclude;
  }
  /* 注意：不能用 mix-blend-mode！它会创建隔离上下文，导致 backdrop-filter 失效 */
  .lg-border { opacity: 0.6; }
  .lg-border--overlay { opacity: 1; }
  .lg-glare {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    pointer-events: none;
    opacity: 0;
    transition: all 0.2s ease-out;
  }
`

/** 注入全局样式（幂等） */
export function injectGlassStyles() {
  if (document.getElementById('lg-glass-styles')) return
  const style = document.createElement('style')
  style.id = 'lg-glass-styles'
  style.textContent = GLASS_STYLES
  document.head.appendChild(style)
}
