import { useCallback, useEffect, useState } from 'react';
import { api } from './api/client';
import { Console } from './components/Console';
import { Login } from './components/Login';
import { loadSession, saveSession, type Session } from './session';

export function App() {
  const [session, setSession] = useState<Session | null>(() => {
    const s = loadSession();
    api.setToken(s?.token ?? null);
    return s;
  });
  const [notice, setNotice] = useState<string | null>(null);

  const signOut = useCallback((reason?: string) => {
    api.setToken(null);
    saveSession(null);
    setSession(null);
    setNotice(reason ?? null);
  }, []);

  useEffect(() => {
    api.setUnauthorizedHandler(() => signOut('Your session has expired. Please sign in again.'));
  }, [signOut]);

  if (!session) {
    return (
      <>
        {notice && <p role="alert" className="notice">{notice}</p>}
        <Login
          onSignedIn={(s) => {
            api.setToken(s.token);
            saveSession(s);
            setNotice(null);
            setSession(s);
          }}
        />
      </>
    );
  }
  return <Console session={session} onSignOut={signOut} />;
}
