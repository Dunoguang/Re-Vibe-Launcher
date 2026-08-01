/**
 * liquid-glass.js — 纯 JS 液态玻璃 Web Component（light DOM 版）
 * 零依赖、零构建。<script type="module"> 直接引入。
 *
 * 重要架构：视觉层放在 light DOM 而非 shadow DOM。
 * 原因：Chrome 中 shadow DOM 内的 backdrop-filter 会被祖先 transform 抑制，
 * 而 light DOM 中两者共存正常。样式通过注入的全局 CSS（.lg- 前缀）提供。
 *
 * 用法：
 *   <liquid-glass mode="prominent" elasticity="0.3"><div>内容</div></liquid-glass>
 */
import { injectGlassStyles } from './modules/shadow-styles.js'
import { createGlassFilterSvg, getMapByMode } from './modules/glass-filter.js'
import { displacementMap, polarDisplacementMap, prominentDisplacementMap } from './modules/displacement.js'
import { computeTransform, computeBorderGradient, computeGlare } from './modules/effects.js'
import { MouseTracker } from './modules/mouse-tracker.js'

const MAPS = { displacementMap, polarDisplacementMap, prominentDisplacementMap }

const DEFAULTS = {
  displacementScale: 70,
  blurAmount: 0.0625,
  saturation: 140,
  aberrationIntensity: 2,
  elasticity: 0.15,
  cornerRadius: 100,
  padding: '24px 32px',
  mode: 'standard',
  borderHighlight: true, // 边框高光开关（false 时完全透明）
  overLight: false,
}

const ATTR_MAP = {
  'displacement-scale': 'displacementScale',
  'blur-amount': 'blurAmount',
  saturation: 'saturation',
  'aberration-intensity': 'aberrationIntensity',
  elasticity: 'elasticity',
  'corner-radius': 'cornerRadius',
  padding: 'padding',
  mode: 'mode',
  'over-light': 'overLight',
  'no-border': 'borderHighlight',
}

const NUMERIC_ATTRS = new Set([
  'displacementScale', 'blurAmount', 'saturation',
  'aberrationIntensity', 'elasticity', 'cornerRadius',
])

class LiquidGlassElement extends HTMLElement {
  #opts = { ...DEFAULTS }
  #tracker = null
  #resizeObserver = null
  #filterId = ''
  #hovered = false
  #active = false
  #mouse = { x: 0, y: 0, offsetX: 0, offsetY: 0 }
  #els = {}
  #built = false

  static get observedAttributes() { return Object.keys(ATTR_MAP) }

  constructor() {
    super()
    this.#filterId = 'lg' + Math.random().toString(36).slice(2, 9)
  }

  /* ---------- 构建 light DOM 骨架 ---------- */
  #buildDOM() {
    if (this.#built) return
    injectGlassStyles()

    const shell = document.createElement('div')
    shell.className = 'lg-shell'
    const glass = document.createElement('div')
    glass.className = 'lg-glass'
    glass.innerHTML = `
      <span class="lg-warp"></span>
      <div class="lg-content"></div>
      <span class="lg-border"></span>
      <span class="lg-border--overlay"></span>
      <span class="lg-glare"></span>`
    shell.appendChild(glass)
    this.appendChild(shell)

    // 把用户原有子元素移动到内容区
    const content = glass.querySelector('.lg-content')
    while (this.childNodes.length > 1) {
      content.appendChild(this.firstChild)
    }

    this.#els = {
      shell,
      glass,
      warp: glass.querySelector('.lg-warp'),
      content,
      border: glass.querySelector('.lg-border'),
      borderOverlay: glass.querySelector('.lg-border--overlay'),
      glare: glass.querySelector('.lg-glare'),
    }
    this.#built = true
  }

  /* ---------- 生命周期 ---------- */
  connectedCallback() {
    this.#buildDOM()
    this.#applyStatic()
    this.#setupResizeObserver()
    this.#setupTracking()
    this.#bindPointerEvents()
  }

  disconnectedCallback() {
    this.#tracker?.destroy()
    this.#resizeObserver?.disconnect()
    this.#tracker = null
    this.#resizeObserver = null
  }

  attributeChangedCallback(name, oldV, newV) {
    if (oldV === newV) return
    const key = ATTR_MAP[name]
    if (key === "overLight") {
      this.#opts.overLight = newV !== null
    } else if (key === 'borderHighlight') {
      this.#opts.borderHighlight = newV === null
    } else if (key === "padding") {
      this.#opts.padding = newV
    } else if (key === "mode") {
      this.#opts.mode = newV
    } else if (NUMERIC_ATTRS.has(key)) {
      this.#opts[key] = parseFloat(newV)
    }
    // 元素尚未构建时跳过（connectedCallback 会完整初始化）
    if (this.#built) this.#applyStatic()
  }

  /* ---------- 对外 API ---------- */
  get options() { return { ...this.#opts } }

  set options(o) {
    this.#opts = { ...DEFAULTS, ...o }
    this.#applyStatic()
    this.#render()
  }

  setMousePosition(x, y) {
    this.#tracker?.setPosition(x, y)
  }

  destroy() {
    this.disconnectedCallback()
  }

  /* ---------- 静态参数：SVG 滤镜 + backdrop-filter ---------- */
  #applyStatic() {
    const o = this.#opts
    const size = this.#measure()
    const mapUrl = getMapByMode(o.mode, null, MAPS)

    // warp：磨砂 + 折射滤镜；SVG 定义直接嵌入 warp（与应用元素一体，始终跟随）
    const warp = this.#els.warp
    warp.innerHTML = createGlassFilterSvg({
      id: this.#filterId,
      mapUrl,
      displacementScale: o.displacementScale,
      aberrationIntensity: o.aberrationIntensity,
      blurAmount: o.blurAmount,
      width: size.width,
      height: size.height,
    })
    const isFF = navigator.userAgent.toLowerCase().includes('firefox')
    // 0 时完全关闭折射滤镜（displacement 和 aberration 都为 0 则无折射）
    const hasRefraction = o.displacementScale > 0 || o.aberrationIntensity > 0
    warp.style.filter = (!isFF && hasRefraction) ? `url(#${this.#filterId})` : 'none'
    // 磨砂强度：blurAmount=0 → blur(0px)（无模糊但保留饱和度），平滑过渡无跳变
    const blurPx = o.blurAmount * 32
    warp.style.backdropFilter = `blur(${blurPx.toFixed(2)}px) saturate(${o.saturation}%)`

    // cornerRadius 为 0~100 百分比
    const radius = Math.round(Math.min(size.width, size.height) / 2 * (o.cornerRadius / 100))
    const glass = this.#els.glass
    glass.style.borderRadius = `${radius}px`
    glass.style.padding = o.padding
    glass.style.boxShadow = o.borderHighlight === false ? 'none' : (o.overLight ? '0px 16px 70px rgba(0,0,0,0.75)' : '0px 12px 40px rgba(0,0,0,0.25)')
      ? '0px 16px 70px rgba(0,0,0,0.75)'
      : '0px 12px 40px rgba(0,0,0,0.25)'

    // 边框高光开关：false 时隐藏高光描边与光晕
    const showHL = o.borderHighlight !== false
    this.#els.border.style.display = showHL ? '' : 'none'
    this.#els.borderOverlay.style.display = showHL ? '' : 'none'
    this.#els.glare.style.display = showHL ? '' : 'none'
    this.#els.content.style.textShadow = showHL ? '' : 'none'
  }

  /* ---------- 每帧动态渲染（鼠标跟随） ---------- */
  #render = () => {
    const o = this.#opts
    const glass = this.#els.glass
    const rect = glass.getBoundingClientRect()
    const size = { width: rect.width, height: rect.height }
    const mouse = { x: this.#mouse.x, y: this.#mouse.y }
    const offset = { x: this.#mouse.offsetX, y: this.#mouse.offsetY }

    // 弹性 transform 在 light DOM 的 shell 上（不抑制 backdrop-filter）
    // 弹性=0 时完全无 transform（只保留点击按压反馈），避免恒等 transform 残留
    const t = computeTransform(mouse, rect, size, {
      elasticity: o.elasticity,
      active: this.#active,
      onClick: this.#opts.onClick,
    })
    this.#els.shell.style.transform = o.elasticity <= 0
      ? (this.#active && this.#opts.onClick ? "scale(0.96)" : "none")
      : t

    this.#els.border.style.background = computeBorderGradient(offset, 1)
    this.#els.borderOverlay.style.background = computeBorderGradient(offset, 2.6)

    if (this.#opts.onClick) {
      const glare = this.#els.glare
      const hot = this.#hovered || this.#active
      glare.style.opacity = hot ? (this.#active ? 0.8 : 0.4) : '0'
      glare.style.backgroundImage = computeGlare(this.#active ? 1 : 0.5)
    }
  }

  /* ---------- 测量 ---------- */
  #measure() {
    const r = this.#els.glass.getBoundingClientRect()
    return { width: Math.max(r.width, 10), height: Math.max(r.height, 10) }
  }

  /* ---------- ResizeObserver ---------- */
  #setupResizeObserver() {
    this.#resizeObserver?.disconnect()
    this.#resizeObserver = new ResizeObserver(() => {
      this.#applyStatic()
      this.#render()
    })
    this.#resizeObserver.observe(this.#els.glass)
  }

  /* ---------- 鼠标追踪（rAF 节流 + 单一数据源） ---------- */
  #setupTracking() {
    this.#tracker?.destroy()
    const attr = this.getAttribute('mouse-container')
    const host = attr ? (document.querySelector(attr) || this) : this
    this.#tracker = new MouseTracker({
      el: host,
      target: this.#els.glass,
      onFrame: (state) => {
        this.#mouse = state
        this.#render()
      },
    })
  }

  /* ---------- 指针事件 ---------- */
  #bindPointerEvents() {
    const glass = this.#els.glass
    glass.addEventListener('pointerenter', () => { this.#hovered = true; this.#render() })
    glass.addEventListener('pointerleave', () => { this.#hovered = false; this.#render() })
    glass.addEventListener('pointerdown', () => { this.#active = true; this.#render() })
    const up = () => { this.#active = false; this.#render() }
    glass.addEventListener('pointerup', up)
    glass.addEventListener('pointercancel', up)
  }
}

/* 注册组件 */
export function defineLiquidGlass(name = 'liquid-glass') {
  if (!customElements.get(name)) {
    customElements.define(name, LiquidGlassElement)
  }
  return name
}

defineLiquidGlass()
export { LiquidGlassElement }
