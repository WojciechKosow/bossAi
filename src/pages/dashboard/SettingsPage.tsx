import { useAuth } from "@/features/auth/context/AuthContext";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useState } from "react";

const SettingsPage = () => {
  const { user } = useAuth();

  return (
    <div className="max-w-5xl mx-auto space-y-12">
      {/* HEADER */}
      <div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          Settings
        </h1>
        <p className="text-muted-foreground mt-2">
          Manage your account, security and preferences.
        </p>
      </div>

      {/* ACCOUNT SECTION */}
      <Section title="Account">
        <div className="grid gap-6 md:grid-cols-2">
          <InfoCard label="Display name" value={user?.displayName || "—"} />
          <InfoCard label="Email" value={user?.email || "—"} />

          <InfoCard label="Account status" value="Active" />
          <InfoCard label="Member since" value="2026" />
        </div>

        <div className="pt-6">
          <Link to="/dashboard/billing">
            <Button variant="outline">Go to Billing</Button>
          </Link>
        </div>
      </Section>

      {/* SECURITY */}
      <Section title="Security">
        <div className="space-y-6">
          <div className="flex items-center justify-between border border-border rounded-xl p-5 bg-card">
            <div>
              <p className="font-medium">Change password</p>
              <p className="text-sm text-muted-foreground">
                Update your account password.
              </p>
            </div>
            <Link to="/reset-password">
              <Button variant="outline">Update</Button>
            </Link>
          </div>

          <div className="flex items-center justify-between border border-border rounded-xl p-5 bg-card">
            <div>
              <p className="font-medium">Change email</p>
              <p className="text-sm text-muted-foreground">
                Update your account email address.
              </p>
            </div>
            <Button variant="outline" disabled>
              Coming soon
            </Button>
          </div>
        </div>
      </Section>

      {/* PREFERENCES */}
      <Section title="Preferences">
        <div className="space-y-6">
          <PreferenceToggle
            title="Email notifications"
            description="Receive updates about product changes."
          />

          <PreferenceToggle
            title="Marketing emails"
            description="Get product updates and promotions."
          />

          {/* <div className="flex items-center justify-between border border-border rounded-xl p-5 bg-card">
            <div>
              <p className="font-medium">Language</p>
              <p className="text-sm text-muted-foreground">
                Select your preferred language.
              </p>
            </div>
            <select className="bg-muted border border-border rounded-md px-3 py-2 text-sm">
              <option>English</option>
              <option>Polish</option>
            </select>
          </div> */}
        </div>
      </Section>

      {/* DANGER ZONE */}
      <Section title="Danger zone">
        <div className="space-y-6">
          <div className="flex items-center justify-between border border-destructive/30 bg-destructive/5 rounded-xl p-5">
            <div>
              <p className="font-medium text-destructive">Delete account</p>
              <p className="text-sm text-muted-foreground">
                Permanently delete your account and all data.
              </p>
            </div>
            <Button variant="destructive">Delete</Button>
          </div>
        </div>
      </Section>
    </div>
  );
};

export default SettingsPage;

/* ================= COMPONENTS ================= */

const Section = ({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) => {
  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
      {children}
    </div>
  );
};

const InfoCard = ({ label, value }: { label: string; value: string }) => {
  return (
    <div className="border border-border rounded-xl p-5 bg-card">
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="mt-1 font-medium">{value}</p>
    </div>
  );
};

const PreferenceToggle = ({
  title,
  description,
}: {
  title: string;
  description: string;
}) => {
  const [enabled, setEnabled] = useState(true);

  return (
    <div className="flex items-center justify-between border border-border rounded-xl p-5 bg-card">
      <div>
        <p className="font-medium">{title}</p>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      <button
        onClick={() => setEnabled(!enabled)}
        className={`w-12 h-6 rounded-full transition relative ${
          enabled ? "bg-primary" : "bg-muted"
        }`}
      >
        <div
          className={`absolute top-1 left-1 w-4 h-4 rounded-full bg-background transition ${
            enabled ? "translate-x-6" : ""
          }`}
        />
      </button>
    </div>
  );
};
