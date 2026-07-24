import { downloadFile } from "../api";
import { useToast } from "@/components/ui/toast";

/**
 * Downloads `url` as `filename` without ever leaving the page. A plain
 * `<a href download>` doesn't work for these (cross-origin, bearer-token
 * protected) URLs — see `downloadFile` for why. Drop-in replacement for
 * `<a href={url} download>`: same children, same styling, just a working
 * click handler.
 */
export const DownloadLink = ({
  url,
  filename,
  className,
  children,
  onClick,
}: {
  url: string;
  filename: string;
  className?: string;
  children: React.ReactNode;
  /** Extra click handler, e.g. stopPropagation inside a clickable card. */
  onClick?: (e: React.MouseEvent) => void;
}) => {
  const toast = useToast();

  const handleClick = async (e: React.MouseEvent) => {
    e.preventDefault();
    onClick?.(e);
    try {
      await downloadFile(url, filename);
    } catch {
      toast.error("Download failed. Please try again.");
    }
  };

  return (
    <a href={url} onClick={handleClick} className={className} title="Download">
      {children}
    </a>
  );
};
