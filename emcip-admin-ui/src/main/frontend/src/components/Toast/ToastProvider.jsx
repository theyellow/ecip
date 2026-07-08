import { createContext, useCallback, useState } from 'react';
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

  return (
    <ToastContext.Provider value={{ addToast }}>
      {children}
      <div className={styles.container}>
        {toasts.map(toast => (
          <Toast key={toast.id} {...toast} onDismiss={dismissToast} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}
