import axios from 'axios';

const API_BASE_URL = (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Add auth token to requests if available
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('auth_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Simple 401 handling: clear session and redirect immediately.
api.interceptors.response.use(
  (response: any) => response,
  (error: any) => {
    const status = error?.response?.status;
    if (status === 401) {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('user');
      if (typeof window !== 'undefined') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export interface RegisterPayload {
  email: string;
  password: string;
  name: string;
  phone: string;
  user_type: 'RIDER' | 'DRIVER';
}

export interface LoginPayload {
  email: string;
  password: string;
}

export interface LatLng {
  latitude: number;
  longitude: number;
}

export const userApi = {
  register: (data: RegisterPayload) => api.post('/user/register', data),
  login: (data: LoginPayload) => api.post('/user/login', data),
  getProfile: (userId: string) => api.get(`/user/profile/${userId}`),
  logout: () => api.post('/user/logout', {}),
};

export const stationApi = {
  getStationsAlongRoute: (origin: string, destination: string, routePoints: LatLng[]) =>
    api.post('/station/route', { origin, destination, route_points: routePoints}),
  getAllStations: () => api.get('/station'),
};

export const driverApi = {
  registerRoute: (data: any) => api.post('/driver/register-route', data),
  updateLocation: (driverId: string, data: any) => api.post(`/driver/${driverId}/location`, data),
  getDriverInfo: (driverId: string) => api.get(`/driver/${driverId}`),
  getDashboard: (driverId: string) => api.get(`/driver/dashboard/${driverId}`),
  rateRider: (data: any) => api.post(`/driver/${data.driverId}/rate-rider`, data),
};

export const riderApi = {
  registerRideRequest: (data: any) => api.post('/rider/ride-request', data),
  getRideStatus: (rideRequestId: string) => api.get(`/rider/ride-request/${rideRequestId}`),
  cancelRideRequest: (rideRequestId: string) => api.post(`/rider/ride-request/${rideRequestId}/cancel`),
  listRideRequests: (riderId: string) => api.get(`/rider/ride-requests/${riderId}`),
  deleteRideRequest: (rideRequestId: string) => api.delete(`/rider/ride-request/${rideRequestId}`),
  rateRider: (data: any) => api.post(`/rider/${data.riderId}/rate`, data),
};

export const matchingApi = {
  matchRiderWithDriver: (data: any) => api.post('/match', data),
  getMatchStatus: (matchId: string) => api.get(`/match/${matchId}`),
};

export const tripApi = {
  getTripInfo: (tripId: string) => api.get(`/trip/${tripId}`),
  recordPickup: (tripId: string, data: any) => api.post(`/trip/${tripId}/pickup`, data),
  recordDropoff: (tripId: string, data: any) => api.post(`/trip/${tripId}/dropoff`, data),
};

export const locationApi = {
  updateLocation: (driverId: string, data: any) => api.post(`/location/${driverId}`, data),
  findNearbyDrivers: (latitude: number, longitude: number, radiusKm: number) =>
    api.get(`/location/nearby?latitude=${latitude}&longitude=${longitude}&radiusKm=${radiusKm}`),
};

export default api;
