import { createRoot } from 'react-dom/client';
import App from './App';

const container = document.getElementById('root');

if (!container) {
  throw new Error(
    'member-mfe host-harness: root container "#root" was not found in the document',
  );
}

createRoot(container).render(<App />);
