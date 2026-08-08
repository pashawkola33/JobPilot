import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/app.css';
import { App } from './App';

const root = createRoot(document.getElementById('root')!);

root.render(
  <StrictMode>
    <App />
  </StrictMode>,
);

// Test seam, dev-only so it never reaches the production bundle. The details sheet must hand
// Telegram's vertical gesture back when it unmounts, and only a real unmount proves that the
// restore lives in the effect cleanup rather than in a close handler.
if (import.meta.env.DEV) {
  (window as unknown as { __unmountApp?: () => void }).__unmountApp = () => root.unmount();
}
