const Footer = () => {
  return (
    <footer className="w-full bg-white border-t">
      <div className="max-w-7xl mx-auto px-8 py-16">
        <div className="flex flex-col md:flex-row justify-between gap-12">
          <div className="max-w-xs">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-8 h-8 rounded-xl bg-gray-900 flex items-center justify-center text-white font-semibold">
                T
              </div>
              <span className="text-lg font-semibold tracking-tight">
                Toucan
              </span>
            </div>
            <p className="text-sm text-gray-500 leading-relaxed">
              Building the future of learning — thoughtfully, simply, and with
              purpose.
            </p>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-10 text-sm">
            <div>
              <h4 className="text-gray-900 font-semibold mb-3">Product</h4>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <a href="#features" className="hover:text-gray-900">
                    Features
                  </a>
                </li>
                <li>
                  <a href="#pricing" className="hover:text-gray-900">
                    Pricing
                  </a>
                </li>
                <li>
                  <a href="#testimonials" className="hover:text-gray-900">
                    Testimonials
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Change Log
                  </a>
                </li>
              </ul>
            </div>

            <div>
              <h4 className="text-gray-900 font-semibold mb-3">Company</h4>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <a href="#" className="hover:text-gray-900">
                    About
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Blog
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Careers
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Press
                  </a>
                </li>
              </ul>
            </div>

            <div>
              <h4 className="text-gray-900 font-semibold mb-3">Support</h4>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Help Center
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Community
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Status
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Guides
                  </a>
                </li>
              </ul>
            </div>

            <div>
              <h4 className="text-gray-900 font-semibold mb-3">Legal</h4>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Privacy Policy
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Terms of Service
                  </a>
                </li>
                <li>
                  <a href="#" className="hover:text-gray-900">
                    Cookies
                  </a>
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div className="mt-14 pt-6 border-t text-sm text-gray-500 flex justify-between">
          <p>© {new Date().getFullYear()} Chico. All rights reserved.</p>
          <p className="hover:text-gray-900 cursor-pointer">Poland / EUR 🇵🇱</p>
        </div>
      </div>
    </footer>
  );
};

export default Footer;
