import { BETA_MODE } from "@/lib/betaMode";
import { BrandLogo } from "@/shared/components/brand/BrandLogo";

const Footer = () => {
  return (
    <footer className="w-full bg-background border-t border-border/60">
      <div className="max-w-7xl mx-auto px-6 md:px-10 py-14 md:py-16">
        <div className="flex flex-col md:flex-row justify-between gap-12">
          {/* Brand */}
          <div className="max-w-xs">
            <div className="mb-4">
              <BrandLogo textClassName="text-[16px]" />
            </div>
            <p className="text-[13px] text-muted-foreground leading-relaxed">
              Turn your ideas and assets into TikTok-ready videos — automatically.
            </p>
          </div>

          {/* Link columns */}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-10 text-sm">
            <FooterColumn
              title="Product"
              links={[
                { label: "Features", href: "#features" },
                ...(!BETA_MODE ? [{ label: "Pricing", href: "#pricing" }] : []),
                {
                  label: BETA_MODE ? "Join Beta" : "How it works",
                  href: BETA_MODE ? "#waitlist" : "#workflow",
                },
              ]}
            />
            <FooterColumn
              title="Company"
              links={[
                { label: "About", href: "#" },
                { label: "Blog", href: "#" },
              ]}
            />
            <FooterColumn
              title="Legal"
              links={[
                { label: "Privacy Policy", href: "#" },
                { label: "Terms of Service", href: "#" },
              ]}
            />
          </div>
        </div>

        <div className="mt-12 pt-6 border-t border-border/50 flex flex-col sm:flex-row justify-between gap-4 text-[12px] text-muted-foreground">
          <p>© {new Date().getFullYear()} Toucan Motion. All rights reserved.</p>
          <p>v0.1 · Early access</p>
        </div>
      </div>
    </footer>
  );
};

export default Footer;

const FooterColumn = ({
  title,
  links,
}: {
  title: string;
  links: { label: string; href: string }[];
}) => (
  <div>
    <h4 className="text-[12px] font-semibold uppercase tracking-widest text-muted-foreground/70 mb-3">
      {title}
    </h4>
    <ul className="space-y-2">
      {links.map((link, i) => (
        <li key={i}>
          <a
            href={link.href}
            className="text-[13px] text-muted-foreground hover:text-foreground transition-colors duration-150"
          >
            {link.label}
          </a>
        </li>
      ))}
    </ul>
  </div>
);
