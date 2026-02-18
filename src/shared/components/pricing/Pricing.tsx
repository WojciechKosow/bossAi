import { motion } from "framer-motion";

const plans = [
  {
    name: "Basic",
    price: "Free",
    description: "Explore courses and learn at your own pace.",
    features: [
      "Access to free courses",
      "Progress tracking",
      "Community support",
    ],
    highlighted: false,
  },
  {
    name: "Plus",
    price: "$9 / mo",
    description: "Unlock full access to most premium courses.",
    features: [
      "Everything in Basic",
      "Access to premium courses",
      "Certificates of completion",
      "Offline lesson downloads",
    ],
    highlighted: true,
  },
  {
    name: "Pro",
    price: "$19 / mo",
    description: "Best for committed learners and professionals.",
    features: [
      "Everything in Plus",
      "1:1 mentor feedback",
      "Career guidance resources",
      "Early access to new courses",
    ],
    highlighted: false,
  },
];

const Pricing = () => {
  return (
    <section id="pricing" className="py-28 bg-white">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <motion.h2
          className="text-3xl md:text-4xl font-semibold text-gray-900"
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          viewport={{ once: true }}
        >
          Simple, transparent pricing.
        </motion.h2>

        <p className="mt-4 text-gray-600 max-w-xl mx-auto">
          Choose the plan that fits your learning journey.
        </p>

        <div className="mt-16 grid grid-cols-1 md:grid-cols-3 gap-8">
          {plans.map((plan, i) => (
            <motion.div
              key={i}
              className={`border rounded-2xl p-8 text-left transition
              ${
                plan.highlighted
                  ? "border-gray-900 shadow-lg scale-[1.03]"
                  : "border-gray-200"
              }
            `}
              initial={{ opacity: 0, y: 30 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: i * 0.1 }}
              viewport={{ once: true }}
            >
              <h3 className="text-xl font-semibold text-gray-900">
                {plan.name}
              </h3>
              <p className="text-3xl font-bold mt-3 text-gray-900">
                {plan.price}
              </p>
              <p className="text-gray-600 mt-3">{plan.description}</p>

              <ul className="mt-8 space-y-3 text-gray-700 text-sm">
                {plan.features.map((f, index) => (
                  <li key={index} className="flex items-center gap-2">
                    <span>•</span> {f}
                  </li>
                ))}
              </ul>

              <button
                className={`mt-10 w-full py-2.5 rounded-xl text-sm font-medium transition
                ${
                  plan.highlighted
                    ? "bg-gray-900 text-white hover:bg-black"
                    : "border border-gray-900 text-gray-900 hover:bg-gray-100"
                }
              `}
              >
                Get Started
              </button>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Pricing;
