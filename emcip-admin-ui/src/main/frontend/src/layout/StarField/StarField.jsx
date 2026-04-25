import { useEffect, useRef } from 'react'
import { useTheme } from '../../theme/ThemeContext'

const TOTAL = 150
const DRIFTERS = 8
const SIZES = [0.5, 1, 1.5]
const PARALLAX = [0.5, 1.5, 3]

export function StarField() {
  const { theme } = useTheme()
  const canvasRef = useRef(null)
  const animRef = useRef(null)
  const mouseRef = useRef({ x: 0, y: 0 })
  const starsRef = useRef(null)

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

    const onMouse = e => { mouseRef.current = { x: e.clientX, y: e.clientY } }
    window.addEventListener('mousemove', onMouse)

    return () => {
      cancelAnimationFrame(animRef.current)
      window.removeEventListener('resize', resize)
      window.removeEventListener('mousemove', onMouse)
    }
  }, [theme])

  if (theme !== 'dark') return null

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'fixed', top: 0, left: 0,
        width: '100vw', height: '100vh',
        zIndex: -1, pointerEvents: 'none',
      }}
    />
  )
}
