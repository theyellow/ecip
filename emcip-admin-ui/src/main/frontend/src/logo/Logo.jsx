export function Logo({ size = 40, className = '' }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      aria-label="The Construct — EMCIP logo"
    >
      {/* Hexagon */}
      <polygon
        points="20,5 32.99,12.5 32.99,27.5 20,35 7.01,27.5 7.01,12.5"
        stroke="currentColor"
        strokeWidth="1.5"
        fill="none"
      />
      {/* Circuit traces from each corner */}
      <path
        d="M20,5 L20,1 M32.99,12.5 L37.32,10 M32.99,27.5 L37.32,30 M20,35 L20,39 M7.01,27.5 L2.68,30 M7.01,12.5 L2.68,10"
        stroke="currentColor"
        strokeWidth="1"
        strokeLinecap="round"
      />
      {/* Eye — outer ellipse */}
      <ellipse
        cx="20" cy="20" rx="6" ry="4"
        stroke="currentColor"
        strokeWidth="1.5"
        fill="none"
      />
      {/* Eye — iris */}
      <circle
        cx="20" cy="20" r="2"
        stroke="currentColor"
        strokeWidth="1"
        fill="none"
      />
      {/* Eye — pupil */}
      <circle cx="20" cy="20" r="0.8" fill="currentColor" />
    </svg>
  )
}
