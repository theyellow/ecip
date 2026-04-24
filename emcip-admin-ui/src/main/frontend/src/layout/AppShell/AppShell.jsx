import { Outlet } from 'react-router-dom'
import { Sidebar } from '../Sidebar/Sidebar'
import { StarField } from '../StarField/StarField'
import styles from './AppShell.module.css'

export function AppShell() {
  return (
    <>
      <StarField />
      <div className={styles.shell}>
        <Sidebar />
        <main className={styles.main}>
          <Outlet />
        </main>
      </div>
    </>
  )
}
