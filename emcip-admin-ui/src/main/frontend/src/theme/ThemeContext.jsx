import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

const ThemeContext = createContext(null)

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(
    () => {
      const stored = localStorage.getItem('emcip-theme')
      if (stored) return stored
      const h = new Date().getHours()
      return (h >= 7 && h < 19) ? 'light' : 'dark'
    }
  )

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('emcip-theme', theme)
  }, [theme])

  // Stable identity: an effect anywhere that depends on `toggleTheme` or on the context object
  // would otherwise re-run on every render. See providerStability.test.jsx.
  const toggleTheme = useCallback(() => setTheme(t => (t === 'light' ? 'dark' : 'light')), [])

  const value = useMemo(() => ({ theme, toggleTheme }), [theme, toggleTheme])

  return (
    <ThemeContext.Provider value={value}>
      {children}
    </ThemeContext.Provider>
  )
}

export function useTheme() {
  return useContext(ThemeContext)
}
