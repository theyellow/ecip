import { Outlet } from 'react-router'
import { Sidebar } from '../Sidebar/Sidebar'
import styles from './AppShell.module.css'

export function AppShell() {
  return (
    <>
      <div className={styles.shell}>
        <Sidebar />
        <main className={styles.main}>
          <div className={styles.surface}>
            <Outlet />
          </div>
        </main>
      </div>
    </>
  )
}
