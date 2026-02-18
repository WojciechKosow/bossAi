import { BookOpen, Users, Award } from "lucide-react";

const features = [
  {
    icon: <BookOpen className="w-8 h-8 text-indigo-600" />,
    title: "High-Quality Courses",
    desc: "Learn from experienced instructors teaching real-world skills.",
  },
  {
    icon: <Users className="w-8 h-8 text-indigo-600" />,
    title: "Learn at Your Own Pace",
    desc: "Study anywhere, anytime. Always available on any device.",
  },
  {
    icon: <Award className="w-8 h-8 text-indigo-600" />,
    title: "Certificates of Completion",
    desc: "Show your progress and achievements with shareable certificates.",
  },
];

const Features = () => {
  return (
    <section id="features" className="py-28 bg-white">
      <div className="max-w-6xl mx-auto px-8 text-center">
        <h2 className="text-3xl md:text-4xl font-semibold text-gray-900">
          Everything you need to succeed.
        </h2>
        <p className="mt-4 text-gray-600 max-w-2xl mx-auto text-lg">
          Tools, progress tracking, and guidance — all in one beautiful
          platform.
        </p>

        <div className="grid md:grid-cols-3 gap-14 mt-20">
          {features.map((item, index) => (
            <div key={index} className="flex flex-col items-center text-center">
              <div className="mb-6">{item.icon}</div>
              <h3 className="text-xl font-medium text-gray-900">
                {item.title}
              </h3>
              <p className="mt-3 text-gray-600 text-base">{item.desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
};

export default Features;
