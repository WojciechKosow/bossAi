import { BETA_MODE } from "@/lib/betaMode";

const Footer = () => {
  return (
    <footer className="w-full bg-background border-t border-border">
      <div className="max-w-7xl mx-auto px-8 py-16">
        <div className="flex flex-col md:flex-row justify-between gap-12">
          {/* BRAND */}
          <div className="max-w-xs">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-xl bg-primary text-primary-foreground flex items-center justify-center font-semibold">
                T
              </div>
              <span className="text-lg font-semibold tracking-tight text-foreground">
                Toucan
              </span>
            </div>
            <p className="text-sm text-muted-foreground leading-relaxed">
              Turn your ideas and assets into TikTok-ready videos — automatically.
            </p>
          </div>

          {/* LINKS */}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-10 text-sm">
            <FooterColumn
              title="Product"
              links={[
                { label: "Features", href: "#features" },
                ...(!BETA_MODE ? [{ label: "Pricing", href: "#pricing" }] : []),
                { label: BETA_MODE ? "Join Beta" : "Testimonials", href: BETA_MODE ? "#waitlist" : "#testimonials" },
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

        <div className="mt-14 pt-6 border-t border-border text-sm text-muted-foreground flex flex-col sm:flex-row justify-between gap-4">
          <p>© {new Date().getFullYear()} Toucan. All rights reserved.</p>
          <p>Poland 🇵🇱</p>
        </div>
      </div>
    </footer>
  );
};

export default Footer;

/* ---------- COLUMN COMPONENT ---------- */

const FooterColumn = ({ title, links }: { title: string; links: { label: string; href: string }[] }) => {
  return (
    <div>
      <h4 className="text-foreground font-semibold mb-3">{title}</h4>
      <ul className="space-y-2 text-muted-foreground">
        {links.map((link, index) => (
          <li key={index}>
            <a href={link.href} className="hover:text-foreground transition">
              {link.label}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
};
