import axios from "@/lib/axios";

export const getActivePlan = async () => {
  const res = await axios.get("http://localhost:8080/api/me/plans/active-plan");
  return res.data;
};

export const getRecentGenerations = async () => {
  const res = await axios.get(
    "http://localhost:8080/api/generations/me?limit=3",
  );
  return res.data;
};
