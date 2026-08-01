/**
 * effects.js — 纯函数计算模块
 * 所有鼠标效果计算均为无副作用的纯函数，便于测试与复用。
 * 算法移植自原 liquid-glass-react，并修复了"边框角度只吃 x"的不对称问题。
 */

const ACTIVATION_ZONE = 200 // 玻璃边缘激活区（像素）

/**
 * 鼠标到玻璃边缘的距离
 * @param {{x:number,y:number}} mouse 鼠标全局坐标
 * @param {DOMRect} rect 玻璃元素 rect
 * @param {{width:number,height:number}} size 玻璃尺寸
 */
function edgeDistance(mouse, rect, size) {
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const dx = Math.max(0, Math.abs(mouse.x - cx) - size.width / 2)
  const dy = Math.max(0, Math.abs(mouse.y - cy) - size.height / 2)
  return Math.sqrt(dx * dx + dy * dy)
}

/** 边缘激活区渐入因子：1=贴边，0=离开激活区 */
export function computeFadeInFactor(mouse, rect, size) {
  if (!mouse || !rect) return 0
  const d = edgeDistance(mouse, rect, size)
  return d > ACTIVATION_ZONE ? 0 : 1 - d / ACTIVATION_ZONE
}

/** 基于鼠标方向的弹性拉伸（scaleX/scaleY） */
export function computeDirectionalScale(mouse, rect, size, elasticity) {
  if (!mouse || !mouse.x || !mouse.y || !rect) return 'scale(1)'
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const deltaX = mouse.x - cx
  const deltaY = mouse.y - cy

  const d = edgeDistance(mouse, rect, size)
  if (d > ACTIVATION_ZONE) return 'scale(1)'
  const fade = 1 - d / ACTIVATION_ZONE

  const centerDist = Math.sqrt(deltaX * deltaX + deltaY * deltaY)
  if (centerDist === 0) return 'scale(1)'

  const nx = deltaX / centerDist
  const ny = deltaY / centerDist
  const stretch = Math.min(centerDist / 300, 1) * elasticity * fade

  const scaleX = 1 + Math.abs(nx) * stretch * 0.3 - Math.abs(ny) * stretch * 0.15
  const scaleY = 1 + Math.abs(ny) * stretch * 0.3 - Math.abs(nx) * stretch * 0.15
  return `scaleX(${Math.max(0.8, scaleX)}) scaleY(${Math.max(0.8, scaleY)})`
}

/** 弹性位移（跟随鼠标微动） */
export function computeElasticTranslation(mouse, rect, elasticity, fadeInFactor) {
  if (!mouse || !rect) return { x: 0, y: 0 }
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  return {
    x: (mouse.x - cx) * elasticity * 0.1 * fadeInFactor,
    y: (mouse.y - cy) * elasticity * 0.1 * fadeInFactor,
  }
}

/** 组合 transform（元素需以 top:50%/left:50% 定位 + translate(-50%) 居中） */
export function computeTransform(mouse, rect, size, opts) {
  const fade = computeFadeInFactor(mouse, rect, size)
  const t = computeElasticTranslation(mouse, rect, opts.elasticity, fade)
  const dir = computeDirectionalScale(mouse, rect, size, opts.elasticity)
  const active = opts.active && opts.onClick ? 'scale(0.96)' : ''
  return `translate(${t.x}px, ${t.y}px) ${active} ${dir}`
}

/**
 * 边框高光渐变背景
 * 改进：角度用 atan2(dy,dx) 同时响应 x/y，不再只吃 x
 * @param {{x:number,y:number}} offset 相对玻璃中心的偏移（约 -100~100）
 */
export function computeBorderGradient(offset, intensity = 1) {
  const ox = offset?.x || 0
  const oy = offset?.y || 0
  // 用 atan2 计算角度，方向同时随 x/y 变化
  const angle = 135 + Math.atan2(oy, ox || 1) * (180 / Math.PI) * 0.6
  const p1 = Math.max(10, 33 + oy * 0.3)
  const p2 = Math.min(90, 66 + oy * 0.4)
  return `linear-gradient(${angle}deg,
    rgba(255,255,255,0) 0%,
    rgba(255,255,255,${(0.12 + Math.abs(ox) * 0.008) * intensity}) ${p1}%,
    rgba(255,255,255,${(0.4 + Math.abs(ox) * 0.012) * intensity}) ${p2}%,
    rgba(255,255,255,0) 100%)`
}

/** 悬停/按压光晕的径向渐变 */
export function computeGlare(opacity) {
  return `radial-gradient(circle at 50% 0%, rgba(255,255,255,${opacity}) 0%, rgba(255,255,255,0) 80%)`
}
