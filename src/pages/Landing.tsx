import SectionCTA from "../shared/components/cta/SectionCTA";
import Features from "../shared/components/features/Features";
import Hero from "../shared/components/hero/Hero";
import Pricing from "../shared/components/pricing/Pricing";
import Testimonials from "../shared/components/testimonials/Testimonials";

const Landing = () => {
  return (
    <>
      <Hero />
      <Features />
      <Testimonials />
      <Pricing />
      <SectionCTA />
    </>
  );
};

export default Landing;
