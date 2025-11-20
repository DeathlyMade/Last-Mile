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

export const userApi = {
  register: (data: RegisterPayload) => api.post('/user/register', data),
  login: (data: LoginPayload) => api.post('/user/login', data),
  getProfile: (userId: string) => api.get(`/user/profile/${userId}`),
};

export const stationApi = {
  getStationsAlongRoute: (origin: string, destination: string) =>
    api.post('/station/route', { origin, destination }),
  getAllStations: () => api.get('/station'),
};

export const driverApi = {
  registerRoute: (data: any) => api.post('/driver/register-route', data),
  updateLocation: (driverId: string, data: any) => api.post(`/driver/${driverId}/location`, data),
  updatePickupStatus: (driverId: string, data: any) => api.post(`/driver/${driverId}/pickup-status`, data),
  getDriverInfo: (driverId: string) => api.get(`/driver/${driverId}`),
};

export const riderApi = {
  registerRideRequest: (data: any) => api.post('/rider/ride-request', data),
  getRideStatus: (rideRequestId: string) => api.get(`/rider/ride-request/${rideRequestId}`),
  cancelRideRequest: (rideRequestId: string) => api.post(`/rider/ride-request/${rideRequestId}/cancel`),
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
