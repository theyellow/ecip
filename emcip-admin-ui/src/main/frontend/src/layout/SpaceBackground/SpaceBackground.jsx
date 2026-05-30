import { useEffect, useRef } from 'react'
import { useTheme } from '../../theme/ThemeContext'
import './SpaceBackground.css'

/* EMCIP — SpaceBackground v3 (the Otherland Sky)
 *
 * Replaces the v2 two-planet / mouse-parallax canvas. See HANDOFF.md.
 *
 * Per-theme composition (locked with design):
 *   dark  → drifting twinkling STARS, low density, EASED parallax + the orb
 *   light → rising warm MOTES, high density, drift + the orb (strong focal bloom)
 * The orb sigil, skyline and fog render in both themes (recoloured via tokens).
 */

const PER_THEME = {
  dark:  { particles: 'stars', density: 0.6, motion: 'parallax' },
  light: { particles: 'motes', density: 1.6, motion: 'drift' },
}

const LAYERS = [
  { par: 0.4, speed: 0.012, size: [0.4, 0.9] },
  { par: 1.0, speed: 0.020, size: [0.7, 1.3] },
  { par: 1.9, speed: 0.030, size: [1.0, 1.8] },
]

export function SpaceBackground() {
  const { theme } = useTheme()
  const canvasRef  = useRef(null)
  const skylineRef = useRef(null)
  const rafRef     = useRef(0)
  const pointer    = useRef({ x: 0.5, y: 0.5 })
  const eased      = useRef({ x: 0, y: 0 })

  // ── Particle field — re-initialises on theme change ──────────────────────
  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')
    const cfg = PER_THEME[theme] || PER_THEME.dark
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const rand = (a, b) => a + Math.random() * (b - a)

    let stars = [], motes = []
    function buildStars() {
      const n = Math.round(150 * cfg.density)
      stars = Array.from({ length: n }, () => {
        const L = LAYERS[Math.floor(Math.random() * LAYERS.length)]
        return {
          x: Math.random() * canvas.width, y: Math.random() * canvas.height,
          r: rand(L.size[0], L.size[1]), base: rand(0.35, 0.95),
          tw: rand(0.6, 1.8), ph: Math.random() * Math.PI * 2,
          drift: L.speed, par: L.par,
          dx: rand(-0.4, 0.2), dy: rand(-0.5, -0.1), warm: Math.random() < 0.12,
        }
      })
    }
    function buildMotes() {
      const n = Math.round(46 * cfg.density)
      motes = Array.from({ length: n }, () => ({
        x: Math.random() * canvas.width, y: Math.random() * canvas.height,
        r: rand(0.8, 2.2), base: rand(0.12, 0.4), tw: rand(0.4, 1.1),
        ph: Math.random() * Math.PI * 2, vy: rand(-0.28, -0.08), vx: rand(-0.12, 0.12),
        sway: rand(0.0006, 0.0018), swayPh: Math.random() * Math.PI * 2,
      }))
    }
    function resize() {
      canvas.width = window.innerWidth
      canvas.height = window.innerHeight
      buildStars(); buildMotes()
    }
    resize()

    function render(t) {
      const w = canvas.width, h = canvas.height
      ctx.clearRect(0, 0, w, h)

      // eased parallax — lerps toward the pointer over ~2s, never 1:1
      const amp = 14
      const tx = cfg.motion === 'parallax' ? (pointer.current.x - 0.5) * amp : 0
      const ty = cfg.motion === 'parallax' ? (pointer.current.y - 0.5) * amp : 0
      eased.current.x += (tx - eased.current.x) * 0.018
      eased.current.y += (ty - eased.current.y) * 0.018

      if (cfg.particles === 'stars') {
        for (const s of stars) {
          if (!reduce && cfg.motion !== 'off') {
            s.x = (s.x + s.dx * s.drift * 16 + w) % w
            s.y = (s.y + s.dy * s.drift * 16 + h) % h
          }
          const tw = 0.45 + 0.55 * Math.sin(t * 0.001 * s.tw + s.ph)
          const a = Math.max(0, Math.min(1, s.base * tw))
          const px = s.x + eased.current.x * s.par
          const py = s.y + eased.current.y * s.par
          ctx.beginPath(); ctx.arc(px, py, s.r, 0, Math.PI * 2)
          ctx.fillStyle = s.warm ? `rgba(245,210,130,${a})` : `rgba(232,240,255,${a})`
          ctx.fill()
          if (s.r > 1.3 && a > 0.7) {
            ctx.beginPath(); ctx.arc(px, py, s.r * 2.6, 0, Math.PI * 2)
            ctx.fillStyle = s.warm ? `rgba(245,210,130,${(a - 0.7) * 0.18})`
                                   : `rgba(210,225,255,${(a - 0.7) * 0.16})`
            ctx.fill()
          }
        }
      } else {
        for (const m of motes) {
          if (!reduce) {
            m.y += m.vy
            m.x += m.vx + Math.sin(t * m.sway + m.swayPh) * 0.3
            if (m.y < -4) { m.y = h + 4; m.x = Math.random() * w }
            if (m.x < -4) m.x = w + 4
            if (m.x > w + 4) m.x = -4
          }
          const tw = 0.5 + 0.5 * Math.sin(t * 0.001 * m.tw + m.ph)
          const a = Math.max(0, Math.min(1, m.base * tw))
          const px = m.x + eased.current.x * 0.6
          const py = m.y + eased.current.y * 0.6
          ctx.beginPath(); ctx.arc(px, py, m.r, 0, Math.PI * 2)
          ctx.fillStyle = `rgba(190,150,70,${a})`
          ctx.shadowBlur = 6; ctx.shadowColor = 'rgba(212,168,73,0.5)'
          ctx.fill(); ctx.shadowBlur = 0
        }
      }
      rafRef.current = requestAnimationFrame(render)
    }

    const onMove = e => {
      pointer.current.x = e.clientX / window.innerWidth
      pointer.current.y = e.clientY / window.innerHeight
    }
    window.addEventListener('resize', resize)
    if (cfg.motion === 'parallax') window.addEventListener('pointermove', onMove)
    rafRef.current = requestAnimationFrame(render)
    return () => {
      cancelAnimationFrame(rafRef.current)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onMove)
    }
  }, [theme])

  // ── Light up a few skyline windows (once) ────────────────────────────────
  useEffect(() => {
    const NS = 'http://www.w3.org/2000/svg'
    const skyline = skylineRef.current
    const near = skyline.querySelector('.sb-mono-near')
    const wins = document.createElementNS(NS, 'g')
    const cellW = 13, cellH = 19, padX = 7, padY = 18, winW = 5, winH = 8
    near.querySelectorAll('rect').forEach(tower => {
      const tx = +tower.getAttribute('x'), tw = +tower.getAttribute('width')
      const ty = +tower.getAttribute('y'), th = +tower.getAttribute('height')
      const x0 = Math.max(tx, 0) + padX, x1 = tx + tw - padX
      for (let x = x0; x + winW <= x1; x += cellW) {
        for (let y = ty + padY; y + winH <= ty + th - 8; y += cellH) {
          const r = Math.random()
          if (r < 0.55) continue
          const wn = document.createElementNS(NS, 'rect')
          wn.setAttribute('x', x.toFixed(1)); wn.setAttribute('y', y.toFixed(1))
          wn.setAttribute('width', winW); wn.setAttribute('height', winH)
          let cls = 'sb-win ' + (r < 0.82 ? 'dim' : 'lit')
          if (r > 0.955) {
            cls += ' toggle'
            wn.style.setProperty('--d', (22 + Math.random() * 22).toFixed(0) + 's')
            wn.style.animationDelay = '-' + (Math.random() * 30).toFixed(0) + 's'
          }
          wn.setAttribute('class', cls)
          wins.appendChild(wn)
        }
      }
    })
    skyline.appendChild(wins)
    return () => wins.remove()
  }, [])

  return (
    <div className="sb-root" data-testid="space-background" aria-hidden="true">
      <div className="sb-grad" />
      <canvas ref={canvasRef} className="sb-canvas" data-testid="sky-canvas" />

      {/* The Construct orb / eye sigil */}
      <svg className="sb-orb" viewBox="-300 -300 600 600">
        <defs>
          <radialGradient id="sbOrbCore" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%"   className="sb-core-1" stopOpacity="0.95" />
            <stop offset="38%"  className="sb-core-1" stopOpacity="0.45" />
            <stop offset="100%" className="sb-core-2" stopOpacity="0" />
          </radialGradient>
          <radialGradient id="sbOrbBloom" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%"   className="sb-bloom-1" stopOpacity="0.30" />
            <stop offset="45%"  className="sb-bloom-2" stopOpacity="0.08" />
            <stop offset="100%" className="sb-bloom-2" stopOpacity="0" />
          </radialGradient>
          <clipPath id="sbEyeClip"><path d="M -130 0 Q 0 -74 130 0 Q 0 74 -130 0 Z" /></clipPath>
        </defs>

        <circle cx="0" cy="0" r="290" fill="url(#sbOrbBloom)" />

        {/* outer slow ring system */}
        <g className="sb-g-spin">
          <circle className="sb-ring-soft" cx="0" cy="0" r="282" strokeWidth="0.6" opacity="0.40" strokeDasharray="1 13" />
          <circle className="sb-ring-soft" cx="0" cy="0" r="250" strokeWidth="0.5" opacity="0.45" strokeDasharray="2 7" />
          <polygon className="sb-ring-soft" strokeWidth="0.8" opacity="0.45" points="0,-238 206,-119 206,119 0,238 -206,119 -206,-119" />
          <g className="sb-ring" strokeWidth="0.5" opacity="0.32">
            <line x1="0" y1="-238" x2="0" y2="-292" />
            <line x1="206" y1="-119" x2="252" y2="-146" />
            <line x1="206" y1="119" x2="252" y2="146" />
            <line x1="0" y1="238" x2="0" y2="292" />
            <line x1="-206" y1="119" x2="-252" y2="146" />
            <line x1="-206" y1="-119" x2="-252" y2="-146" />
          </g>
        </g>

        {/* inner counter-rotating ring system */}
        <g className="sb-g-spin-rev">
          <circle className="sb-ring" cx="0" cy="0" r="196" strokeWidth="0.8" opacity="0.55" />
          <circle className="sb-ring" cx="0" cy="0" r="168" strokeWidth="0.8" opacity="0.55" />
          <polygon className="sb-ring" strokeWidth="1" opacity="0.5" points="0,-150 130,-75 130,75 0,150 -130,75 -130,-75" />
          <g className="sb-ring" strokeWidth="0.6" opacity="0.45">
            <line x1="0" y1="-150" x2="0" y2="-210" />
            <line x1="130" y1="-75" x2="182" y2="-105" />
            <line x1="130" y1="75" x2="182" y2="105" />
            <line x1="0" y1="150" x2="0" y2="210" />
            <line x1="-130" y1="75" x2="-182" y2="105" />
            <line x1="-130" y1="-75" x2="-182" y2="-105" />
          </g>
        </g>

        {/* core glow + the Eye (iris/pupil clipped inside the almond) */}
        <g className="sb-g-breathe">
          <circle cx="0" cy="0" r="150" fill="url(#sbOrbCore)" />
          <g className="sb-g-blink">
            <g className="sb-g-iris">
              <g clipPath="url(#sbEyeClip)">
                <circle className="sb-eye-iris" cx="0" cy="0" r="46" opacity="0.62" />
                <circle className="sb-eye-iris-2" cx="0" cy="0" r="30" opacity="0.7" />
                <g className="sb-g-pupil">
                  <circle className="sb-eye-pupil" cx="0" cy="0" r="17" />
                  <circle className="sb-hot" cx="-7" cy="-9" r="3.2" opacity="0.6" />
                </g>
              </g>
              <path className="sb-eye-line" strokeWidth="2.4" fill="none" d="M -130 0 Q 0 -74 130 0 Q 0 74 -130 0 Z" />
            </g>
          </g>
        </g>
      </svg>

      {/* Foggy skyline (windows injected in effect) */}
      <svg ref={skylineRef} className="sb-skyline" viewBox="0 0 1600 460" preserveAspectRatio="xMidYMax slice">
        <g className="sb-mono-far" opacity="0.6">
          <rect x="90" y="150" width="26" height="320" />
          <rect x="200" y="220" width="18" height="250" />
          <rect x="350" y="110" width="34" height="360" />
          <rect x="430" y="190" width="20" height="280" />
          <rect x="1180" y="160" width="40" height="320" />
          <rect x="1270" y="220" width="26" height="260" />
          <rect x="1360" y="120" width="46" height="350" />
          <rect x="1470" y="190" width="30" height="290" />
        </g>
        <g className="sb-mono-near">
          <rect x="-30" y="250" width="78" height="220" />
          <rect x="120" y="320" width="48" height="150" />
          <rect x="1500" y="230" width="92" height="240" />
          <rect x="1430" y="310" width="46" height="160" opacity="0.85" />
        </g>
      </svg>

      <div className="sb-fog" />
    </div>
  )
}

export default SpaceBackground
