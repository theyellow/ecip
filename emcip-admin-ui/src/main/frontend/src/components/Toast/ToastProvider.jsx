import { createContext, useCallback, useMemo, useState } from 'react';
import Toast from './Toast';
import styles from './Toast.module.css';

export const ToastContext = createContext(null);

const MAX_VISIBLE = 5;
let nextId = 0;

export default function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  const addToast = useCallback((type, message, options = {}) => {
    const id = ++nextId;
    const toast = { id, type, message, duration: options.duration };
    setToasts(prev => {
      const next = [...prev, toast];
      return next.length > MAX_VISIBLE ? next.slice(-MAX_VISIBLE) : next;
    });
    return id;
  }, []);

  const dismissToast = useCallback(id => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  // Memoized because this provider re-renders on every toast, and consumers may depend on the
  // context object itself rather than destructuring `addToast` out of it. See
  // providerStability.test.jsx for why an unstable context value is a request loop here.
  const value = useMemo(() => ({ addToast }), [addToast]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className={styles.container}>
        {toasts.map(toast => (
          <Toast key={toast.id} {...toast} onDismiss={dismissToast} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}
