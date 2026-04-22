# Epic 5.4 — Admin UI (React SPA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a new `emcip-admin-ui` Maven module — a Spring Boot app serving a React/TypeScript SPA — with four views: Login, Tenants, Policy Rules, and Audit Log.

**Architecture:** A Spring Boot app at port 14009 serves the React SPA as static resources from `src/main/resources/static/`. The React app is built by `frontend-maven-plugin` during `mvn package`. The SPA calls `emcip-admin-api` (port 9087) via `fetch` with a JWT in memory (never localStorage). All API calls go through a single `api/` wrapper module for easy testing.

**Tech Stack:** Spring Boot 4, React 18, TypeScript, Vite, shadcn/ui, Cypress (E2E), frontend-maven-plugin, Node.js 20.

---

### Task 1: emcip-admin-ui Maven module scaffold

**Files:**
- Create: `emcip-admin-ui/pom.xml`
- Create: `emcip-admin-ui/src/main/java/io/emcip/admin/ui/AdminUiApplication.java`
- Create: `emcip-admin-ui/src/main/resources/application.yml`
- Modify: `pom.xml` (root) — add `emcip-admin-ui` to `<modules>`

- [ ] **Step 1: Add module to root pom.xml**

In the root `pom.xml`, in the `<modules>` section, add:
```xml
<module>emcip-admin-ui</module>
```

- [ ] **Step 2: Create emcip-admin-ui/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.emcip</groupId>
        <artifactId>community-intelligence-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>emcip-admin-ui</artifactId>
    <name>EMCIP Admin UI</name>
    <description>React SPA served via Spring Boot — Admin interface for EMCIP</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>1.15.0</version>
                <configuration>
                    <workingDirectory>src/main/frontend</workingDirectory>
                    <installDirectory>target</installDirectory>
                </configuration>
                <executions>
                    <execution>
                        <id>install-node-npm</id>
                        <goals><goal>install-node-and-npm</goal></goals>
                        <configuration>
                            <nodeVersion>v20.12.2</nodeVersion>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm-install</id>
                        <goals><goal>npm</goal></goals>
                        <configuration><arguments>install</arguments></configuration>
                    </execution>
                    <execution>
                        <id>npm-build</id>
                        <goals><goal>npm</goal></goals>
                        <phase>generate-resources</phase>
                        <configuration><arguments>run build</arguments></configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-react-build</id>
                        <phase>process-resources</phase>
                        <goals><goal>copy-resources</goal></goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                            <resources>
                                <resource>
                                    <directory>src/main/frontend/dist</directory>
                                    <filtering>false</filtering>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create AdminUiApplication.java**

```java
package io.emcip.admin.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AdminUiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminUiApplication.class, args);
    }
}
```

- [ ] **Step 4: Create application.yml**

```yaml
server:
  port: 14009

spring:
  application:
    name: emcip-admin-ui

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 5: Verify Java module compiles (without frontend)**

```bash
mvn compile -pl emcip-admin-ui -Dfrontend.skip=true
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/pom.xml \
        emcip-admin-ui/src/main/java/io/emcip/admin/ui/AdminUiApplication.java \
        emcip-admin-ui/src/main/resources/application.yml \
        pom.xml
git commit -m "feat(5.4): scaffold emcip-admin-ui Spring Boot module (port 14009)"
```

---

### Task 2: React app scaffold

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/package.json`
- Create: `emcip-admin-ui/src/main/frontend/vite.config.ts`
- Create: `emcip-admin-ui/src/main/frontend/tsconfig.json`
- Create: `emcip-admin-ui/src/main/frontend/index.html`
- Create: `emcip-admin-ui/src/main/frontend/src/main.tsx`
- Create: `emcip-admin-ui/src/main/frontend/src/App.tsx`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "emcip-admin-ui",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.23.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "^5.4.5",
    "vite": "^5.2.11"
  }
}
```

- [ ] **Step 2: Create vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:9087',
        changeOrigin: true,
      },
    },
  },
})
```

- [ ] **Step 3: Create tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"],
  "exclude": ["node_modules"]
}
```

- [ ] **Step 4: Create index.html**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>EMCIP Admin</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: Create src/main.tsx**

```typescript
import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
```

- [ ] **Step 6: Create src/App.tsx (routing shell)**

```typescript
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginView from './views/LoginView'
import TenantsView from './views/TenantsView'
import PolicyRulesView from './views/PolicyRulesView'
import AuditLogView from './views/AuditLogView'
import { useAuth } from './auth/AuthContext'
import { AuthProvider } from './auth/AuthContext'

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { token } = useAuth()
  return token ? <>{children}</> : <Navigate to="/login" replace />
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginView />} />
      <Route path="/tenants" element={<ProtectedRoute><TenantsView /></ProtectedRoute>} />
      <Route path="/policy-rules" element={<ProtectedRoute><PolicyRulesView /></ProtectedRoute>} />
      <Route path="/audit-log" element={<ProtectedRoute><AuditLogView /></ProtectedRoute>} />
      <Route path="/" element={<Navigate to="/tenants" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  )
}
```

- [ ] **Step 7: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/
git commit -m "feat(5.4): scaffold React app with routing shell"
```

---

### Task 3: Auth context and API client

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/auth/AuthContext.tsx`
- Create: `emcip-admin-ui/src/main/frontend/src/api/client.ts`
- Create: `emcip-admin-ui/src/main/frontend/src/api/tenants.ts`
- Create: `emcip-admin-ui/src/main/frontend/src/api/policyRules.ts`
- Create: `emcip-admin-ui/src/main/frontend/src/api/auditLog.ts`

- [ ] **Step 1: Create AuthContext.tsx — JWT stored in memory, not localStorage**

```typescript
import React, { createContext, useContext, useState } from 'react'

interface AuthContextValue {
  token: string | null
  login: (token: string) => void
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null)

  return (
    <AuthContext.Provider value={{
      token,
      login: setToken,
      logout: () => setToken(null),
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
```

- [ ] **Step 2: Create api/client.ts — base fetch with auth header**

```typescript
const ADMIN_API_BASE = '/api'  // proxied to localhost:9087 in dev, same origin in prod

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export async function apiFetch<T>(
  path: string,
  token: string | null,
  options: RequestInit = {}
): Promise<T> {
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options.headers,
  }
  const res = await fetch(`${ADMIN_API_BASE}${path}`, { ...options, headers })
  if (!res.ok) {
    throw new ApiError(res.status, `HTTP ${res.status}: ${res.statusText}`)
  }
  return res.json() as Promise<T>
}
```

- [ ] **Step 3: Create api/tenants.ts**

```typescript
import { apiFetch } from './client'

export interface Tenant {
  id: string
  name: string
  description?: string
  llmModelOverride?: string
  createdAt: string
}

export const tenantsApi = {
  list: (token: string) =>
    apiFetch<Tenant[]>('/tenants', token),

  create: (token: string, data: { name: string; description?: string }) =>
    apiFetch<Tenant>('/tenants', token, {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  delete: (token: string, id: string) =>
    apiFetch<void>(`/tenants/${id}`, token, { method: 'DELETE' }),
}
```

- [ ] **Step 4: Create api/policyRules.ts**

```typescript
import { apiFetch } from './client'

export interface PolicyRule {
  id: string
  name: string
  targetIntent: string
  action: string
  minConfidence: number
  ruleVersion: number
  active: boolean
  effectiveFrom?: string
  effectiveTo?: string
}

export const policyRulesApi = {
  listActive: (token: string) =>
    apiFetch<PolicyRule[]>('/policy-rules', token),

  createVersion: (token: string, rule: Partial<PolicyRule>) =>
    apiFetch<PolicyRule>('/policy-rules', token, {
      method: 'POST',
      body: JSON.stringify(rule),
    }),

  getHistory: (token: string, ruleName: string) =>
    apiFetch<PolicyRule[]>(`/policy-rules/history/${encodeURIComponent(ruleName)}`, token),
}
```

- [ ] **Step 5: Create api/auditLog.ts**

```typescript
import { apiFetch } from './client'

export interface AuditEvent {
  id: string
  eventType: string
  sourceEventId?: string
  timestamp: string
  payload?: Record<string, unknown>
}

export interface AuditPage {
  content: AuditEvent[]
  totalElements: number
  number: number
  size: number
}

export const auditLogApi = {
  list: (token: string, page = 0, size = 20) =>
    apiFetch<AuditPage>(`/audit/events?page=${page}&size=${size}`, token),
}
```

- [ ] **Step 6: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/auth/ \
        emcip-admin-ui/src/main/frontend/src/api/
git commit -m "feat(5.4): add AuthContext and API client wrappers"
```

---

### Task 4: LoginView

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/views/LoginView.tsx`

- [ ] **Step 1: Create LoginView.tsx**

```typescript
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { apiFetch } from '../api/client'

export default function LoginView() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    try {
      const { token } = await apiFetch<{ token: string }>(
        '/auth/token',
        null,
        { method: 'POST', body: JSON.stringify({ username, password }) }
      )
      login(token)
      navigate('/tenants')
    } catch {
      setError('Invalid credentials')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ maxWidth: 400, margin: '100px auto', padding: 24 }}>
      <h1>EMCIP Admin</h1>
      <form onSubmit={handleSubmit}>
        <div>
          <label>Username<br />
            <input value={username} onChange={e => setUsername(e.target.value)} required />
          </label>
        </div>
        <div>
          <label>Password<br />
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} required />
          </label>
        </div>
        {error && <p style={{ color: 'red' }}>{error}</p>}
        <button type="submit" disabled={loading}>
          {loading ? 'Logging in…' : 'Login'}
        </button>
      </form>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/views/LoginView.tsx
git commit -m "feat(5.4): add LoginView"
```

---

### Task 5: TenantsView

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/views/TenantsView.tsx`

- [ ] **Step 1: Create TenantsView.tsx**

```typescript
import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { tenantsApi, Tenant } from '../api/tenants'

export default function TenantsView() {
  const { token, logout } = useAuth()
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [newName, setNewName] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    tenantsApi.list(token).then(setTenants).catch(() => setError('Failed to load tenants'))
  }, [token])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!token || !newName.trim()) return
    try {
      const created = await tenantsApi.create(token, { name: newName.trim() })
      setTenants(prev => [...prev, created])
      setNewName('')
    } catch {
      setError('Failed to create tenant')
    }
  }

  async function handleDelete(id: string) {
    if (!token) return
    await tenantsApi.delete(token, id)
    setTenants(prev => prev.filter(t => t.id !== id))
  }

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>Tenants</h1>
        <nav>
          <a href="/policy-rules">Policy Rules</a> |{' '}
          <a href="/audit-log">Audit Log</a> |{' '}
          <button onClick={logout}>Logout</button>
        </nav>
      </div>
      {error && <p style={{ color: 'red' }}>{error}</p>}
      <form onSubmit={handleCreate} style={{ marginBottom: 16 }}>
        <input
          placeholder="New tenant name"
          value={newName}
          onChange={e => setNewName(e.target.value)}
          required
        />
        <button type="submit">Create</button>
      </form>
      <table border={1} cellPadding={8}>
        <thead><tr><th>ID</th><th>Name</th><th>Created</th><th></th></tr></thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.id}>
              <td>{t.id}</td>
              <td>{t.name}</td>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
              <td><button onClick={() => handleDelete(t.id)}>Delete</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/views/TenantsView.tsx
git commit -m "feat(5.4): add TenantsView"
```

---

### Task 6: PolicyRulesView and AuditLogView

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/views/PolicyRulesView.tsx`
- Create: `emcip-admin-ui/src/main/frontend/src/views/AuditLogView.tsx`

- [ ] **Step 1: Create PolicyRulesView.tsx**

```typescript
import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { policyRulesApi, PolicyRule } from '../api/policyRules'

export default function PolicyRulesView() {
  const { token } = useAuth()
  const [rules, setRules] = useState<PolicyRule[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    policyRulesApi.listActive(token).then(setRules).catch(() => setError('Failed to load rules'))
  }, [token])

  return (
    <div style={{ padding: 24 }}>
      <h1>Policy Rules</h1>
      {error && <p style={{ color: 'red' }}>{error}</p>}
      <table border={1} cellPadding={8}>
        <thead>
          <tr>
            <th>Name</th><th>Intent</th><th>Action</th>
            <th>Min Confidence</th><th>Version</th><th>Effective From</th>
          </tr>
        </thead>
        <tbody>
          {rules.map(r => (
            <tr key={r.id}>
              <td>{r.name}</td>
              <td>{r.targetIntent}</td>
              <td>{r.action}</td>
              <td>{r.minConfidence}</td>
              <td>v{r.ruleVersion}</td>
              <td>{r.effectiveFrom ? new Date(r.effectiveFrom).toLocaleString() : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 2: Create AuditLogView.tsx**

```typescript
import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { auditLogApi, AuditEvent } from '../api/auditLog'

export default function AuditLogView() {
  const { token } = useAuth()
  const [events, setEvents] = useState<AuditEvent[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    auditLogApi.list(token, page, 20)
      .then(data => {
        setEvents(data.content)
        setTotalPages(Math.ceil(data.totalElements / data.size))
      })
      .catch(() => setError('Failed to load audit log'))
  }, [token, page])

  return (
    <div style={{ padding: 24 }}>
      <h1>Audit Log</h1>
      {error && <p style={{ color: 'red' }}>{error}</p>}
      <table border={1} cellPadding={8}>
        <thead>
          <tr><th>Event Type</th><th>Source Event ID</th><th>Timestamp</th></tr>
        </thead>
        <tbody>
          {events.map(e => (
            <tr key={e.id}>
              <td>{e.eventType}</td>
              <td>{e.sourceEventId ?? '—'}</td>
              <td>{new Date(e.timestamp).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ marginTop: 16 }}>
        <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>
          Previous
        </button>
        {' '}Page {page + 1} of {totalPages}{' '}
        <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>
          Next
        </button>
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/src/views/
git commit -m "feat(5.4): add PolicyRulesView and AuditLogView"
```

---

### Task 7: Build and verify

**Files:** No new files — verify existing setup works end to end.

- [ ] **Step 1: Build the React app**

```bash
cd emcip-admin-ui/src/main/frontend
npm install
npm run build
```

Expected: `dist/` directory created with `index.html` and assets.

- [ ] **Step 2: Build the Maven module**

```bash
mvn package -pl emcip-admin-ui
```

Expected: `emcip-admin-ui/target/emcip-admin-ui-0.1.0-SNAPSHOT.jar` created.

- [ ] **Step 3: Run the admin UI service**

```bash
mvn spring-boot:run -pl emcip-admin-ui
```

- [ ] **Step 4: Verify the app loads**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:14009/
```

Expected: `200`

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/.gitignore
git commit -m "feat(5.4): verify Admin UI build and serve"
```

> Add a `.gitignore` in `src/main/frontend/` with `node_modules/` and `dist/`.

---

### Task 8: Cypress E2E test

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/cypress/e2e/login.cy.ts`
- Create: `emcip-admin-ui/src/main/frontend/cypress.config.ts`

- [ ] **Step 1: Add Cypress to package.json devDependencies**

```bash
cd emcip-admin-ui/src/main/frontend
npm install --save-dev cypress @types/cypress
```

- [ ] **Step 2: Create cypress.config.ts**

```typescript
import { defineConfig } from 'cypress'

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:14009',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: false,
  },
})
```

- [ ] **Step 3: Create login test**

```typescript
// cypress/e2e/login.cy.ts
describe('Login flow', () => {
  it('redirects to /tenants after login', () => {
    cy.visit('/login')
    cy.get('input[type="text"], input:not([type="password"])').first().type('admin')
    cy.get('input[type="password"]').type('admin')
    cy.get('button[type="submit"]').click()
    cy.url().should('include', '/tenants')
  })

  it('shows error on bad credentials', () => {
    cy.visit('/login')
    cy.get('input:not([type="password"])').first().type('admin')
    cy.get('input[type="password"]').type('wrongpassword')
    cy.get('button[type="submit"]').click()
    cy.contains('Invalid credentials').should('be.visible')
  })
})
```

- [ ] **Step 4: Run Cypress (requires running admin-ui + admin-api)**

```bash
cd emcip-admin-ui/src/main/frontend
npx cypress run --headless
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add emcip-admin-ui/src/main/frontend/cypress/ \
        emcip-admin-ui/src/main/frontend/cypress.config.ts \
        emcip-admin-ui/src/main/frontend/package.json \
        emcip-admin-ui/src/main/frontend/package-lock.json
git commit -m "test(5.4): add Cypress E2E login tests"
```

---

### Verification

```bash
# Build the full module
mvn package -pl emcip-admin-ui

# Start admin-api (dependency)
mvn spring-boot:run -pl emcip-admin-api &

# Start admin-ui
mvn spring-boot:run -pl emcip-admin-ui

# Verify
curl -s -o /dev/null -w "%{http_code}" http://localhost:14009/
# → 200

# Open in browser
open http://localhost:14009
# Login with admin/admin → see Tenants page
```
