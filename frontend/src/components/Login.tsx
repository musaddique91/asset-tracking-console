import { useState, type FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type { Session } from '../session';

export function Login({ onSignedIn }: { onSignedIn(s: Session): void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api.login(username.trim(), password);
      if (r.user.role === 'INGEST') {
        setError('The feed account cannot use the console. Sign in as an operator or supervisor.');
        return;
      }
      onSignedIn({ token: r.accessToken, username: r.user.username, role: r.user.role, expiresAt: Date.now() + r.expiresInSec * 1000 });
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? 'Wrong username or password.' : 'The server cannot be reached. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form onSubmit={submit} aria-labelledby="login-title" noValidate>
        <h1 id="login-title">Zone Watch</h1>
        <p className="muted">Operator console</p>
        <label htmlFor="username">Username</label>
        <input id="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        <label htmlFor="password">Password</label>
        <input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
