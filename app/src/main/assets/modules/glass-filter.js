/**
 * glass-filter.js — SVG 滤镜引擎
 * 生成内联 SVG <filter>，实现液态玻璃的：
 *   1. 边缘折射位移（feDisplacementMap）
 *   2. RGB 通道分离的色差（chromatic aberration）
 *   3. 边缘遮罩（radialGradient + feComponentTransfer）
 * 算法移植自原 liquid-glass-react。
 */

/**
 * 生成 SVG 滤镜字符串
 * @param {object} p
 * @param {string} p.id      滤镜唯一 ID
 * @param {string} p.mapUrl  位移贴图 dataURL
 * @param {number} p.displacementScale 位移强度
 * @param {number} p.aberrationIntensity 色差强度
 * @param {number} p.width   玻璃宽度
 * @param {number} p.height  玻璃高度
 * @returns {string} 完整 <svg> 字符串
 */
export function createGlassFilterSvg({ id, mapUrl, displacementScale, aberrationIntensity, blurAmount = 0.1, width, height }) {
  // 位移方向：标准贴图取反，shader 贴图取正（与原库一致）
  const sign = -1
  const edgeStop = Math.max(30, 80 - aberrationIntensity * 2)
  // 磨砂强度控制色差柔化模糊：blurAmount<=0 时完全不模糊
  const blurStd = blurAmount > 0 ? Math.max(0.1, 0.5 - aberrationIntensity * 0.1) : 0

  return `
  <svg style="position:absolute;width:${width}px;height:${height}px" aria-hidden="true">
    <defs>
      <radialGradient id="${id}-edge-mask" cx="50%" cy="50%" r="50%">
        <stop offset="0%" stop-color="black" stop-opacity="0"/>
        <stop offset="${edgeStop}%" stop-color="black" stop-opacity="0"/>
        <stop offset="100%" stop-color="white" stop-opacity="1"/>
      </radialGradient>

      <filter id="${id}" x="-35%" y="-35%" width="170%" height="170%" color-interpolation-filters="sRGB">
        <feImage x="0" y="0" width="100%" height="100%" result="DISPLACEMENT_MAP"
          href="${mapUrl}" preserveAspectRatio="xMidYMid slice"/>

        <!-- 用位移贴图自身生成边缘强度 -->
        <feColorMatrix in="DISPLACEMENT_MAP" type="matrix" values="0.3 0.3 0.3 0 0  0.3 0.3 0.3 0 0  0.3 0.3 0.3 0 0  0 0 0 1 0" result="EDGE_INTENSITY"/>
        <feComponentTransfer in="EDGE_INTENSITY" result="EDGE_MASK">
          <feFuncA type="discrete" tableValues="0 ${(aberrationIntensity * 0.05).toFixed(2)} 1"/>
        </feComponentTransfer>

        <!-- 中心保持原样 -->
        <feOffset in="SourceGraphic" dx="0" dy="0" result="CENTER_ORIGINAL"/>

        <!-- 红通道位移 -->
        <feDisplacementMap in="SourceGraphic" in2="DISPLACEMENT_MAP"
          scale="${displacementScale * sign}" xChannelSelector="R" yChannelSelector="B" result="RED_DISPLACED"/>
        <feColorMatrix in="RED_DISPLACED" type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="RED_CHANNEL"/>

        <!-- 绿通道位移（轻微偏移） -->
        <feDisplacementMap in="SourceGraphic" in2="DISPLACEMENT_MAP"
          scale="${(displacementScale * (sign - aberrationIntensity * 0.05)).toFixed(2)}" xChannelSelector="R" yChannelSelector="B" result="GREEN_DISPLACED"/>
        <feColorMatrix in="GREEN_DISPLACED" type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="GREEN_CHANNEL"/>

        <!-- 蓝通道位移（偏移最大，产生色差） -->
        <feDisplacementMap in="SourceGraphic" in2="DISPLACEMENT_MAP"
          scale="${(displacementScale * (sign - aberrationIntensity * 0.1)).toFixed(2)}" xChannelSelector="R" yChannelSelector="B" result="BLUE_DISPLACED"/>
        <feColorMatrix in="BLUE_DISPLACED" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="BLUE_CHANNEL"/>

        <!-- screen 混合三通道 = 色差 -->
        <feBlend in="GREEN_CHANNEL" in2="BLUE_CHANNEL" mode="screen" result="GB_COMBINED"/>
        <feBlend in="RED_CHANNEL" in2="GB_COMBINED" mode="screen" result="RGB_COMBINED"/>

        <!-- 轻微模糊柔化色差 -->
        <feGaussianBlur in="RGB_COMBINED" stdDeviation="${blurStd.toFixed(2)}" result="ABERRATED_BLURRED"/>

        <!-- 色差只作用于边缘 -->
        <feComposite in="ABERRATED_BLURRED" in2="EDGE_MASK" operator="in" result="EDGE_ABERRATION"/>

        <!-- 中心反转遮罩 -->
        <feComponentTransfer in="EDGE_MASK" result="INVERTED_MASK">
          <feFuncA type="table" tableValues="1 0"/>
        </feComponentTransfer>
        <feComposite in="CENTER_ORIGINAL" in2="INVERTED_MASK" operator="in" result="CENTER_CLEAN"/>

        <!-- 边缘色差 + 干净中心 -->
        <feComposite in="EDGE_ABERRATION" in2="CENTER_CLEAN" operator="over"/>
      </filter>
    </defs>
  </svg>`
}

/**
 * 位移贴图选择
 * @param {'standard'|'polar'|'prominent'|'shader'} mode
 * @param {string} shaderMapUrl shader 模式动态贴图
 * @param {object} maps 三个预置贴图
 */
export function getMapByMode(mode, shaderMapUrl, maps) {
  switch (mode) {
    case 'polar': return maps.polarDisplacementMap
    case 'prominent': return maps.prominentDisplacementMap
    case 'shader': return shaderMapUrl || maps.displacementMap
    case 'standard':
    default: return maps.displacementMap
  }
}
