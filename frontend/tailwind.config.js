/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ether: {
          bg:    '#070b14',
          panel: '#0d1322',
          line:  '#1a2540',
          accent:'#56d4ff',
          warn:  '#ffb347',
          err:   '#ff5577',
          ok:    '#4be3a3',
        },
      },
      fontFamily: {
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'monospace'],
      },
    },
  },
  plugins: [],
};
