/**
 * mouse-tracker.js — 鼠标/指针追踪器
 * 改进点（相对原 React 版）：
 *   1. 单一数据源：内部统一维护 MouseState，外部可整体接管
 *   2. pointermove 代替 mousemove：支持触摸/笔/鼠标
 *   3. rAF 节流：事件只写数据，渲染帧内统一回调，杜绝重渲染风暴
 */
export class MouseTracker {
  /** @type {HTMLElement} 监听目标 */
  #el = null
  /** @type {HTMLElement} 计算基准（玻璃本体） */
  #target = null
  /** @type {Function} 每帧回调 */
  #onFrame = null
  #rafId = 0
  #enabled = true

  /** 单一数据源 */
  #state = { x: 0, y: 0, offsetX: 0, offsetY: 0, rect: null }

  /**
   * @param {object} cfg
   * @param {HTMLElement} cfg.el 事件监听元素
   * @param {HTMLElement} cfg.target 坐标计算基准元素
   * @param {Function} cfg.onFrame 每帧回调 (state) => void
   */
  constructor({ el, target, onFrame }) {
    this.#el = el
    this.#target = target
    this.#onFrame = onFrame
    el.addEventListener('pointermove', this.#handle, { passive: true })
  }

  #handle = (e) => {
    if (!this.#enabled) return
    this.#state.x = e.clientX
    this.#state.y = e.clientY
    if (this.#rafId) cancelAnimationFrame(this.#rafId)
    this.#rafId = requestAnimationFrame(() => this.#tick())
  }

  /** 渲染帧内：计算相对偏移并回调 */
  #tick = () => {
    this.#rafId = 0
    const target = this.#target
    if (!target) return
    const rect = target.getBoundingClientRect()
    this.#state.rect = rect
    if (rect.width > 0 && rect.height > 0) {
      const cx = rect.left + rect.width / 2
      const cy = rect.top + rect.height / 2
      this.#state.offsetX = ((this.#state.x - cx) / rect.width) * 100
      this.#state.offsetY = ((this.#state.y - cy) / rect.height) * 100
    }
    this.#onFrame?.({
      x: this.#state.x,
      y: this.#state.y,
      offsetX: this.#state.offsetX,
      offsetY: this.#state.offsetY,
      rect,
    })
  }

  /** 切换监听 */
  setEnabled(v) {
    this.#enabled = v
    if (!v) this.#rafId && cancelAnimationFrame(this.#rafId)
  }

  /** 外部接管坐标（单一数据源入口，不会产生分裂状态） */
  setPosition(x, y) {
    this.#state.x = x
    this.#state.y = y
    if (this.#rafId) cancelAnimationFrame(this.#rafId)
    this.#rafId = requestAnimationFrame(() => this.#tick())
  }

  destroy() {
    this.#el.removeEventListener('pointermove', this.#handle)
    this.#rafId && cancelAnimationFrame(this.#rafId)
    this.#el = null
    this.#target = null
    this.#onFrame = null
  }
}
