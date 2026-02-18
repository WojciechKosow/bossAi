import { Route } from "react-router";
import { BrowserRouter, Routes } from "react-router";
import MainLayout from "../shared/layouts/MainLayout";
import Landing from "../pages/Landing";

const AppRouter = () => {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<Landing />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
};

export default AppRouter;
