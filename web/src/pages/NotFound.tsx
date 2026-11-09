import { FileQuestion } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { EmptyState } from "../components/ui";

export function NotFound() {
  const { t } = useTranslation();
  return (
    <EmptyState icon={<FileQuestion className="size-12" />} title={t("notFound.title")}>
      <p className="text-sm">{t("notFound.text")}</p>
      <Link to="/" className="mt-2 text-sm font-medium text-zinc-400 hover:underline">{t("notFound.home")}</Link>
    </EmptyState>
  );
}
