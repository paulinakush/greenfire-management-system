/* ---------------------------------------------------------
   TEMA — paleta institucional Green Fire para las interfaces
   privadas (Sucursal / Fábrica). Cargar después del script
   de Tailwind CDN y antes del app-source de cada página.
--------------------------------------------------------- */

window.GF_COLORS = {
  institutional: "#00AE4C",
  deep: "#1A312B",
  medium: "#29683D",
  neutral: "#C3C3C3",
};

tailwind.config = {
  theme: {
    extend: {
      colors: {
        institutional: "#00AE4C",
        deep: "#1A312B",
        medium: "#29683D",
        neutral: "#C3C3C3",
      },
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', "sans-serif"],
        display: ['"Space Grotesk"', "sans-serif"],
        accent: ['"Instrument Serif"', "serif"],
      },
    },
  },
};
