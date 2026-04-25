import { useEffect, useRef, useState } from 'react'
import { useTheme } from '../../theme/ThemeContext'
import styles from './SpaceBackground.module.css'

const TOTAL = 150
const DRIFTERS = 8
const SIZES = [0.5, 1, 1.5]
const PARALLAX = [0.5, 1.5, 3]

export function SpaceBackground() {
  const { theme } = useTheme()
  const canvasRef = useRef(null)
  const animRef = useRef(null)
  const mouseRef = useRef({ x: 0, y: 0 })
  const starsRef = useRef(null)
  const [planetOffset, setPlanetOffset] = useState({ x: 0, y: 0 })

  useEffect(() => {
    if (theme !== 'dark') {
      if (animRef.current) cancelAnimationFrame(animRef.current)
      return
    }

    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')

    const resize = () => {
      canvas.width = window.innerWidth
      canvas.height = window.innerHeight
    }
    resize()
    window.addEventListener('resize', resize)

    starsRef.current = Array.from({ length: TOTAL }, (_, i) => ({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      size: SIZES[Math.floor(Math.random() * 3)],
      opacity: 0.4 + Math.random() * 0.6,
      drift: i < DRIFTERS,
      vx: (Math.random() - 0.5) * 0.04,
      vy: (Math.random() - 0.5) * 0.04,
      layer: Math.floor(Math.random() * 3),
    }))

    const draw = () => {
      const w = canvas.width
      const h = canvas.height
      ctx.clearRect(0, 0, w, h)
      const mx = (mouseRef.current.x / w - 0.5) * 10
      const my = (mouseRef.current.y / h - 0.5) * 10
      for (const star of starsRef.current) {
        if (star.drift) {
          star.x = (star.x + star.vx + w) % w
          star.y = (star.y + star.vy + h) % h
        }
        const px = star.x + mx * PARALLAX[star.layer]
        const py = star.y + my * PARALLAX[star.layer]
        ctx.beginPath()
        ctx.arc(px, py, star.size, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(255,255,255,${star.opacity})`
        ctx.fill()
      }
      animRef.current = requestAnimationFrame(draw)
    }
    draw()

    const onMouse = e => {
      mouseRef.current = { x: e.clientX, y: e.clientY }
      setPlanetOffset({
        x: (e.clientX / window.innerWidth - 0.5) * 3,
        y: (e.clientY / window.innerHeight - 0.5) * 3,
      })
    }
    window.addEventListener('mousemove', onMouse)

    return () => {
      cancelAnimationFrame(animRef.current)
      window.removeEventListener('resize', resize)
      window.removeEventListener('mousemove', onMouse)
    }
  }, [theme])

  const outerStyle = {
    position: 'fixed', top: 0, left: 0,
    width: '100vw', height: '100vh',
    zIndex: -1, pointerEvents: 'none',
    overflow: 'hidden',
  }

  if (theme === 'light') {
    return (
      <div style={outerStyle} data-testid="space-background">
        <div style={{ position: 'absolute', bottom: -60, right: -60 }} data-testid="sun">
          <svg className={styles.sunPulse} width="280" height="280" viewBox="0 0 280 280">
            <defs>
              <radialGradient id="sunCore" cx="50%" cy="50%" r="50%">
                <stop offset="0%"   stopColor="#fff7c0"/>
                <stop offset="40%"  stopColor="#f5c842"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0.8"/>
              </radialGradient>
              <radialGradient id="corona1" cx="50%" cy="50%" r="50%">
                <stop offset="55%"  stopColor="#f5c842" stopOpacity="0.22"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
              <radialGradient id="corona2" cx="50%" cy="50%" r="50%">
                <stop offset="65%"  stopColor="#f5c842" stopOpacity="0.10"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
              <radialGradient id="corona3" cx="50%" cy="50%" r="50%">
                <stop offset="72%"  stopColor="#f5c842" stopOpacity="0.05"/>
                <stop offset="100%" stopColor="#f5a800" stopOpacity="0"/>
              </radialGradient>
            </defs>
            <ellipse cx="140" cy="140" rx="138" ry="138" fill="url(#corona3)"/>
            <ellipse cx="140" cy="140" rx="118" ry="118" fill="url(#corona2)"/>
            <ellipse cx="140" cy="140" rx="100" ry="100" fill="url(#corona1)"/>
            <circle  cx="140" cy="140" r="76"           fill="url(#sunCore)"/>
          </svg>
        </div>
      </div>
    )
  }

  return (
    <div style={outerStyle} data-testid="space-background">
      <canvas
        ref={canvasRef}
        data-testid="stars-canvas"
        style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '100%' }}
      />

      {/* Planet A — hero, lower-left, ringed */}
      <div
        data-testid="planet-hero"
        style={{
          position: 'absolute', bottom: -100, left: -80,
          transform: `translate(${planetOffset.x * 3}px, ${planetOffset.y * 3}px)`,
          transition: 'transform 0.15s ease-out',
        }}
      >
        <svg className={styles.planetHeroSvg} width="340" height="340" viewBox="0 0 340 340">
          <defs>
            <radialGradient id="planetABody" cx="38%" cy="32%" r="62%">
              <stop offset="0%"   stopColor="#2a6b7c"/>
              <stop offset="55%"  stopColor="#1a3a5c"/>
              <stop offset="100%" stopColor="#0d1a3a"/>
            </radialGradient>
            <radialGradient id="planetAGlow" cx="50%" cy="50%" r="50%">
              <stop offset="68%"  stopColor="#1a3a5c" stopOpacity="0"/>
              <stop offset="100%" stopColor="#00a0c0" stopOpacity="0.22"/>
            </radialGradient>
            <linearGradient id="ringAGrad" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%"   stopColor="#c9a84c" stopOpacity="0.08"/>
              <stop offset="28%"  stopColor="#c9a84c" stopOpacity="0.55"/>
              <stop offset="72%"  stopColor="#7b6cf6" stopOpacity="0.45"/>
              <stop offset="100%" stopColor="#7b6cf6" stopOpacity="0.08"/>
            </linearGradient>
          </defs>
          <circle  cx="170" cy="170" r="164"                             fill="url(#planetAGlow)"/>
          <ellipse cx="170" cy="188" rx="235" ry="40" fill="none" stroke="url(#ringAGrad)" strokeWidth="20" opacity="0.6"/>
          <circle  cx="170" cy="170" r="132"                             fill="url(#planetABody)"/>
        </svg>
      </div>

      {/* Planet B — distant, upper-right */}
      <div
        data-testid="planet-distant"
        style={{
          position: 'absolute', top: -30, right: -30,
          transform: `translate(${-planetOffset.x * 1.5}px, ${planetOffset.y * 1.5}px)`,
          transition: 'transform 0.15s ease-out',
        }}
      >
        <svg className={styles.planetDistantSvg} width="130" height="130" viewBox="0 0 130 130">
          <defs>
            <radialGradient id="planetBBody" cx="34%" cy="28%" r="66%">
              <stop offset="0%"   stopColor="#4a4a7a"/>
              <stop offset="62%"  stopColor="#2a2a50"/>
              <stop offset="100%" stopColor="#15152e"/>
            </radialGradient>
            <radialGradient id="planetBGlow" cx="50%" cy="50%" r="50%">
              <stop offset="68%"  stopColor="#2a2a50" stopOpacity="0"/>
              <stop offset="100%" stopColor="#7b6cf6" stopOpacity="0.18"/>
            </radialGradient>
          </defs>
          <circle cx="65" cy="65" r="63" fill="url(#planetBGlow)"/>
          <circle cx="65" cy="65" r="48" fill="url(#planetBBody)"/>
        </svg>
      </div>
    </div>
  )
}
