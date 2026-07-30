import SectionCTA from "../shared/components/cta/SectionCTA";
import Features from "../shared/components/features/Features";
import Hero from "../shared/components/hero/Hero";
import Pricing from "../shared/components/pricing/Pricing";
import Workflow from "../shared/components/workflow/Workflow";

const Landing = () => {
  return (
    <>
      <Hero />
      <Features />
      <Workflow />
      <Pricing />
      <SectionCTA />
    </>
  );
};

export default Landing;
