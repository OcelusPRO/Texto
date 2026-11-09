import i18n from "i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { initReactI18next } from "react-i18next";
import fr from "../locales/fr";
import en from "../locales/en";

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: { fr: { translation: fr }, en: { translation: en } },
    fallbackLng: "fr",
    supportedLngs: ["fr", "en"],
    interpolation: { escapeValue: false },
    detection: { order: ["localStorage", "navigator"], lookupLocalStorage: "texto-lang", caches: ["localStorage"] },
  });

i18n.on("languageChanged", (l) => { document.documentElement.lang = l; });

export default i18n;
