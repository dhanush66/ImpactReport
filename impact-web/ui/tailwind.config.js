/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        risk: {
          high:   "#dc2626",
          medium: "#d97706",
          low:    "#16a34a",
        },
      },
    },
  },
  plugins: [],
};
