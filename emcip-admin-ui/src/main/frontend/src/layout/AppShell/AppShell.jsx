import { Outlet } from 'react-router-dom'
import { Sidebar } from '../Sidebar/Sidebar'
import { SpaceBackground } from '../SpaceBackground/SpaceBackground'
import styles from './AppShell.module.css'

export function AppShell() {
  return (
    <>
      <SpaceBackground />
      <div className={styles.shell}>
        <Sidebar />
        <main className={styles.main}>
          <Outlet />
        </main>
      </div>
    </>
  )
}
