import { useState, useEffect, useRef } from 'react';
import { Button } from './ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from './ui/dialog';
import { MapPin, Users, CheckCircle2, Plus, Activity, DollarSign, Star, Clock } from 'lucide-react';
import { toast } from 'sonner';
import { MapView, PlaceSearchBox } from './MapView'; 
import { RatingDialog } from './RatingDialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './ui/tabs';
import { useLoadScript } from '@react-google-maps/api';
import { driverApi, locationApi } from '../lib/api';

// Define libraries outside component to prevent re-renders
const libraries: ("places")[] = ["places"];

interface DriverDashboardProps {
  user: any;
}

export function DriverDashboard({ user }: DriverDashboardProps) {
  // Persist route locally to restore after reload if backend does not yet return coordinates
  const ROUTE_STORAGE_KEY = `driver_active_route_${user.id}`;
  // Load Google Maps Script at the top level
  const { isLoaded, loadError } = useLoadScript({
    googleMapsApiKey: import.meta.env.VITE_APP_GOOGLE_MAPS_API_KEY || "",
    libraries,
  });

  const [activeRoute, setActiveRoute] = useState<any>(null);
  
  const [currentLocation, setCurrentLocation] = useState({ lat: 28.6139, lng: 77.2090 });
  const [isLocationResolved, setIsLocationResolved] = useState(false);
  const locationIntervalRef = useRef<any>(null);
  
  const [trips, setTrips] = useState<any[]>([]);
  const [rideHistory, setRideHistory] = useState<any[]>([]);
  const [showRouteDialog, setShowRouteDialog] = useState(false);
  const [locationUpdateInterval, setLocationUpdateInterval] = useState<any>(null);
  const [driverRating, setDriverRating] = useState(4.7);
  const [totalEarnings, setTotalEarnings] = useState(0);
  const [ratingDialogOpen, setRatingDialogOpen] = useState(false);
  const [selectedRiderForRating, setSelectedRiderForRating] = useState<any>(null);

  // Route registration form
  const [destination, setDestination] = useState('');
  const [destinationCoords, setDestinationCoords] = useState<any>(null);
  const [availableSeats, setAvailableSeats] = useState(4);

  useEffect(() => {

    // Attempt to restore previously active route (with destination coords) before contacting backend
    const storedRouteRaw = localStorage.getItem(ROUTE_STORAGE_KEY);
    let restoredCoords: any = null;
    if (storedRouteRaw) {
      try {
        const storedRoute = JSON.parse(storedRouteRaw);
        if (storedRoute?.destination && storedRoute?.destinationCoords?.lat && storedRoute?.destinationCoords?.lng) {
          restoredCoords = storedRoute.destinationCoords;
          setActiveRoute(storedRoute);
          setDestination(storedRoute.destination);
          setDestinationCoords(storedRoute.destinationCoords);
          setAvailableSeats(storedRoute.availableSeats || availableSeats);
        }
      } catch { /* ignore corrupt storage */ }
    }

    // Fetch dashboard from backend to hydrate state (will trigger 401 redirect on expiry via axios interceptor)
    const fetchDashboard = async () => {
      try {
        const { data } = await driverApi.getDashboard(user.id);
        if (data?.success) {
          if (typeof data.driverRating === 'number') setDriverRating(data.driverRating);
          if (typeof data.totalEarnings === 'number') setTotalEarnings(data.totalEarnings);

          const active = Array.isArray(data.activeTrips) ? data.activeTrips.map((t: any) => ({
            id: t.tripId,
            riderId: '',
            riderName: t.riderName,
            riderRating: t.riderRating,
            pickupStation: t.pickupStation,
            destination: t.destination,
            status: t.status,
            pickupTime: t.pickupTimestamp ? new Date(t.pickupTimestamp).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : '',
            fare: t.fare,
          })) : [];
          setTrips(active);

          const history = Array.isArray(data.rideHistory) ? data.rideHistory.map((r: any) => ({
            id: r.tripId,
            date: r.date,
            riderName: r.riderName,
            destination: r.destination,
            fare: r.fare,
            rating: r.ratingGiven,
            duration: r.pickupTimestamp && r.dropoffTimestamp ?
              Math.max(1, Math.round((r.dropoffTimestamp - r.pickupTimestamp) / 60000)) + ' min' : '—',
          })) : [];
          setRideHistory(history);

          if (data.destination) {
            setDestination(data.destination);
            if (typeof data.availableSeats === 'number') setAvailableSeats(data.availableSeats);
            // Prefer coords from restored route first, then current state, then existing activeRoute
            const coords = restoredCoords || destinationCoords || activeRoute?.destinationCoords;
            setActiveRoute({
              id: 'route_from_backend',
              driverId: user.id,
              currentLocation: data.currentLocation ? { latitude: data.currentLocation.latitude, longitude: data.currentLocation.longitude } : currentLocation,
              destination: data.destination,
              destinationCoords: coords,
              availableSeats: data.availableSeats || availableSeats,
              departureTime: new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
            });
          }
        }
      } catch (e) {
        // keep UI usable without backend data
      }
    };

    // Acquire geolocation first; only then fetch dashboard so initial route uses real origin
    const startAfterGeo = () => {
      fetchDashboard();
      locationIntervalRef.current = setInterval(fetchDashboard, 30000);
    };

    if (navigator.geolocation) {
      let geoResolved = false;
      navigator.geolocation.getCurrentPosition(
        (position) => {
          if (geoResolved) return;
          geoResolved = true;
          setCurrentLocation({
            lat: position.coords.latitude,
            lng: position.coords.longitude,
          });
          setIsLocationResolved(true);
          startAfterGeo();
        },
        () => {
          if (geoResolved) return;
          geoResolved = true;
          toast.info('Using default location. Enable location services for accurate positioning.');
          setIsLocationResolved(true);
          startAfterGeo();
        },
        { enableHighAccuracy: true, timeout: 5000 }
      );
      // Fallback: if geolocation stalls beyond timeout, proceed anyway
      setTimeout(() => {
        if (!geoResolved) {
          geoResolved = true;
          setIsLocationResolved(true);
          startAfterGeo();
        }
      }, 5500);
    } else {
      setIsLocationResolved(true);
      startAfterGeo();
    }
    return () => {
      if (locationIntervalRef.current) clearInterval(locationIntervalRef.current);
    };
  }, [user.id, user.name]);

  const handleRegisterRoute = async (e: React.FormEvent) => {
    e.preventDefault();

    // Check if destination is valid
    if (!destination || !destinationCoords) {
      toast.error('Please select a valid destination from the list');
      return;
    }

    try {
      const currentTime = new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
      
      await driverApi.registerRoute({
        driver_id: user.id,
        origin_station: '',
        destination,
        available_seats: availableSeats,
        metro_stations: [],
      });
      // Re-fetch dashboard to ensure persistence reflects backend state
      try {
        const { data: dash } = await driverApi.getDashboard(user.id);
        if (dash?.destination) {
          setActiveRoute({
            id: 'route_from_backend',
            driverId: user.id,
            currentLocation: dash.currentLocation ? { latitude: dash.currentLocation.latitude, longitude: dash.currentLocation.longitude } : currentLocation,
            destination: dash.destination,
            destinationCoords: destinationCoords,
            availableSeats: dash.availableSeats || availableSeats,
            departureTime: currentTime,
          });
          // Persist updated route with coordinates
          localStorage.setItem(ROUTE_STORAGE_KEY, JSON.stringify({
            id: 'route_from_backend',
            driverId: user.id,
            currentLocation,
            destination: dash.destination,
            destinationCoords: destinationCoords,
            availableSeats: dash.availableSeats || availableSeats,
            departureTime: currentTime,
          }));
        } else {
          // Fallback to local construction if backend doesn't yet return route
          setActiveRoute({
            id: 'route_' + Date.now(),
            driverId: user.id,
            currentLocation,
            destination,
            destinationCoords: destinationCoords,
            availableSeats,
            departureTime: currentTime,
          });
          localStorage.setItem(ROUTE_STORAGE_KEY, JSON.stringify({
            id: 'route_' + Date.now(),
            driverId: user.id,
            currentLocation,
            destination,
            destinationCoords: destinationCoords,
            availableSeats,
            departureTime: currentTime,
          }));
        }
      } catch {
        // Fallback
        setActiveRoute({
          id: 'route_' + Date.now(),
          driverId: user.id,
          currentLocation,
          destination,
          destinationCoords: destinationCoords,
          availableSeats,
          departureTime: currentTime,
        });
        localStorage.setItem(ROUTE_STORAGE_KEY, JSON.stringify({
          id: 'route_' + Date.now(),
          driverId: user.id,
          currentLocation,
          destination,
          destinationCoords: destinationCoords,
          availableSeats,
          departureTime: currentTime,
        }));
      }
      setShowRouteDialog(false);
      toast.success('Route registered successfully! Starting location updates...');

      startLocationUpdates();
    } catch (error: any) {
      toast.error('Failed to register route');
    }
  };

  const startLocationUpdates = () => {
    if (locationUpdateInterval) {
      clearInterval(locationUpdateInterval);
    }

    const interval = setInterval(async () => {
      setCurrentLocation(prev => ({
        lat: prev.lat + (Math.random() - 0.5) * 0.001,
        lng: prev.lng + (Math.random() - 0.5) * 0.001,
      }));

      try {
        const { lat, lng } = currentLocation;
        await driverApi.updateLocation(user.id, { driver_id: user.id, latitude: lat, longitude: lng });
        await locationApi.updateLocation(user.id, { latitude: lat, longitude: lng });
      } catch {
        // ignore
      }
    }, 10000);

    setLocationUpdateInterval(interval);
  };

  const handlePickup = async (tripId: string) => {
    try {
      setTrips(prev => prev.map(trip =>
        trip.id === tripId ? { ...trip, status: 'active' } : trip
      ));
      toast.success('Pickup confirmed!');
    } catch (error) {
      toast.error('Failed to confirm pickup');
    }
  };

  const handleDropoff = async (tripId: string) => {
    const trip = trips.find(t => t.id === tripId);
    if (!trip) return;

    try {
      setTrips(prev => prev.map(t =>
        t.id === tripId ? { ...t, status: 'completed' } : t
      ));
      
      setRideHistory(prev => [{
        id: 'h_' + Date.now(),
        date: new Date().toISOString().split('T')[0],
        riderName: trip.riderName,
        destination: trip.destination,
        fare: trip.fare,
        rating: 0,
        duration: '20 min',
      }, ...prev]);

      setTotalEarnings(prev => prev + trip.fare);
      
      toast.success('Drop-off confirmed! Please rate your rider.');
      
      setSelectedRiderForRating(trip);
      setRatingDialogOpen(true);
    } catch (error) {
      toast.error('Failed to confirm drop-off');
    }
  };

  const handleRateRider = (rating: number, feedback: string) => {
    toast.success(`Thank you for rating ${selectedRiderForRating?.riderName}!`);
  };

  // Profile picture upload removed; avatar will use initial only.

  const getTodayEarnings = () => {
    const today = new Date().toISOString().split('T')[0];
    return rideHistory
      .filter(ride => ride.date === today)
      .reduce((sum, ride) => sum + ride.fare, 0);
  };

  const getYesterdayEarnings = () => {
    const yesterday = new Date(Date.now() - 86400000).toISOString().split('T')[0];
    return rideHistory
      .filter(ride => ride.date === yesterday)
      .reduce((sum, ride) => sum + ride.fare, 0);
  };

  if (loadError) return <div>Error loading maps configuration.</div>;

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* Profile Header */}
        <div className="mb-8 bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center gap-6">
            <div className="w-20 h-20 rounded-full bg-gradient-to-br from-blue-500 to-purple-500 flex items-center justify-center text-white text-3xl font-semibold">
              {user.name?.charAt(0).toUpperCase() || 'U'}
            </div>
            <div className="flex-1">
              <h1 className="text-3xl mb-1">{user.name}</h1>
              <div className="flex items-center gap-4 text-gray-600">
                <div className="flex items-center gap-1">
                  <Star className="h-5 w-5 fill-yellow-400 text-yellow-400" />
                  <span>{driverRating.toFixed(1)} Rating</span>
                </div>
                <div className="flex items-center gap-1">
                  <DollarSign className="h-5 w-5 text-green-500" />
                  <span>₹{totalEarnings} Total Earned</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Stats Cards */}
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Today's Earnings</p>
                  <p className="text-2xl mt-1">₹{getTodayEarnings()}</p>
                </div>
                <DollarSign className="h-8 w-8 text-green-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Active Trips</p>
                  <p className="text-2xl mt-1">{trips.filter(t => t.status === 'active').length}</p>
                </div>
                <Activity className="h-8 w-8 text-blue-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Scheduled</p>
                  <p className="text-2xl mt-1">{trips.filter(t => t.status === 'scheduled').length}</p>
                </div>
                <Clock className="h-8 w-8 text-orange-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Available Seats</p>
                  <p className="text-2xl mt-1">{activeRoute?.availableSeats || 0}</p>
                </div>
                <Users className="h-8 w-8 text-purple-500" />
              </div>
            </CardContent>
          </Card>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Map and Route */}
          <div className="lg:col-span-2 space-y-6">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div>
                    <CardTitle>Live Map & Route</CardTitle>
                    <CardDescription>Your current location and destination</CardDescription>
                  </div>
                  <Dialog open={showRouteDialog} onOpenChange={setShowRouteDialog}>
                    <DialogTrigger asChild>
                      <Button>
                        <Plus className="h-4 w-4 mr-2" />
                        Start Route
                      </Button>
                    </DialogTrigger>
                    <DialogContent className="sm:max-w-[425px]">
                      <DialogHeader>
                        <DialogTitle>Start New Route</DialogTitle>
                        <DialogDescription>Set your destination and available seats</DialogDescription>
                      </DialogHeader>
                      <form onSubmit={handleRegisterRoute} className="space-y-4">
                        <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 text-sm">
                          <p className="text-blue-900 mb-1">📍 Current Location</p>
                          <p className="text-blue-700 text-xs">
                            {currentLocation.lat?.toFixed(4)}, {currentLocation.lng?.toFixed(4)}
                          </p>
                          <p className="text-blue-600 text-xs mt-1">
                            Departure: {new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' })}
                          </p>
                        </div>

                        {/* Google Places Autocomplete Integration */}
                        <div className="space-y-2">
                          <Label htmlFor="destination">Target Destination</Label>
                          {isLoaded ? (
                            <PlaceSearchBox
                              placeholder="Search for destination..."
                              onPlaceSelect={(place) => {
                                // Important: This updates the state required for handleRegisterRoute
                                setDestination(place.address);
                                setDestinationCoords(place.coords);
                              }}
                            />
                          ) : (
                            <Input disabled placeholder="Loading maps..." />
                          )}
                        </div>

                        <div className="space-y-2">
                          <Label htmlFor="seats">Available Seats</Label>
                          <Input
                            id="seats"
                            type="number"
                            min="1"
                            max="8"
                            value={availableSeats}
                            onChange={(e) => setAvailableSeats(parseInt(e.target.value))}
                            required
                          />
                        </div>

                        <Button type="submit" className="w-full">Start Route</Button>
                      </form>
                    </DialogContent>
                  </Dialog>
                </div>
              </CardHeader>
              <CardContent>
                {!isLocationResolved ? (
                  <div className="h-[400px] flex items-center justify-center bg-gray-100 rounded-lg">
                    <div className="flex flex-col items-center gap-2">
                      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
                      <p className="text-sm text-gray-500">Acquiring location...</p>
                    </div>
                  </div>
                ) : (
                  <MapView
                    isLoaded={isLoaded}
                    currentLocation={currentLocation}
                    destination={activeRoute ? { ...activeRoute.destinationCoords, name: activeRoute.destination } : undefined}
                    showRoute={!!activeRoute}
                  />
                )}
                {activeRoute && (
                  <div className="mt-4 p-4 bg-blue-50 rounded-lg">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-sm text-gray-600">Destination</p>
                        <p>{activeRoute.destination}</p>
                      </div>
                      <Badge>Active</Badge>
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Trips */}
            <Card>
              <CardHeader>
                <CardTitle>Current Trips</CardTitle>
                <CardDescription>Manage pickups and drop-offs</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {trips.map((trip) => (
                    <div key={trip.id} className="border rounded-lg p-4">
                      <div className="flex items-start justify-between mb-3">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-500 rounded-full flex items-center justify-center text-white">
                            {trip.riderName.charAt(0)}
                          </div>
                          <div>
                            <p>{trip.riderName}</p>
                            <div className="flex items-center gap-1">
                              <Star className="h-3 w-3 fill-yellow-400 text-yellow-400" />
                              <span className="text-xs text-gray-600">{trip.riderRating}</span>
                            </div>
                          </div>
                        </div>
                        <Badge
                          variant={
                            trip.status === 'completed' ? 'default' :
                            trip.status === 'active' ? 'default' : 'secondary'
                          }
                        >
                          {trip.status}
                        </Badge>
                      </div>
                      <div className="space-y-2 mb-3">
                        <div className="flex items-center gap-2 text-sm text-gray-600">
                          <MapPin className="h-4 w-4" />
                          <span>Pickup: {trip.pickupStation}</span>
                        </div>
                        <div className="flex items-center gap-2 text-sm text-gray-600">
                          <MapPin className="h-4 w-4" />
                          <span>Drop: {trip.destination}</span>
                        </div>
                        <div className="flex items-center gap-2 text-sm text-gray-600">
                          <DollarSign className="h-4 w-4" />
                          <span>Fare: ₹{trip.fare}</span>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        {trip.status === 'scheduled' && (
                          <Button
                            size="sm"
                            onClick={() => handlePickup(trip.id)}
                            className="flex-1"
                          >
                            <CheckCircle2 className="h-4 w-4 mr-1" />
                            Confirm Pickup
                          </Button>
                        )}
                        {trip.status === 'active' && (
                          <Button
                            size="sm"
                            onClick={() => handleDropoff(trip.id)}
                            className="flex-1"
                          >
                            <CheckCircle2 className="h-4 w-4 mr-1" />
                            Confirm Drop-off
                          </Button>
                        )}
                      </div>
                    </div>
                  ))}
                  {trips.length === 0 && (
                    <div className="text-center py-8 text-gray-500">
                      <Users className="h-12 w-12 mx-auto mb-3 text-gray-300" />
                      <p>No trips yet</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* History & Earnings */}
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>Earnings Summary</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="bg-gradient-to-br from-green-50 to-emerald-50 p-4 rounded-lg">
                  <p className="text-sm text-gray-600 mb-1">Total Earnings</p>
                  <p className="text-3xl text-green-700">₹{totalEarnings}</p>
                </div>
                <div className="space-y-3">
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Today</span>
                    <span className="text-green-600">₹{getTodayEarnings()}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Yesterday</span>
                    <span>₹{getYesterdayEarnings()}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Total Rides</span>
                    <span>{rideHistory.length}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Avg. per Ride</span>
                    <span>₹{rideHistory.length > 0 ? Math.round(totalEarnings / rideHistory.length) : 0}</span>
                  </div>
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Ride History</CardTitle>
                <CardDescription>Past rides and destinations</CardDescription>
              </CardHeader>
              <CardContent>
                <Tabs defaultValue="recent">
                  <TabsList className="w-full">
                    <TabsTrigger value="recent" className="flex-1">Recent</TabsTrigger>
                    <TabsTrigger value="all" className="flex-1">All</TabsTrigger>
                  </TabsList>
                  <TabsContent value="recent" className="space-y-3 mt-4">
                    {rideHistory.slice(0, 3).map((ride) => (
                      <div key={ride.id} className="border rounded-lg p-3">
                        <div className="flex justify-between items-start mb-2">
                          <div>
                            <p className="text-sm">{ride.riderName}</p>
                            <p className="text-xs text-gray-500">{ride.destination}</p>
                          </div>
                          <span className="text-sm text-green-600">₹{ride.fare}</span>
                        </div>
                        <div className="flex justify-between items-center text-xs text-gray-500">
                          <span>{ride.date}</span>
                          <span>{ride.duration}</span>
                        </div>
                      </div>
                    ))}
                  </TabsContent>
                  <TabsContent value="all" className="space-y-3 mt-4 max-h-96 overflow-y-auto">
                    {rideHistory.map((ride) => (
                      <div key={ride.id} className="border rounded-lg p-3">
                        <div className="flex justify-between items-start mb-2">
                          <div>
                            <p className="text-sm">{ride.riderName}</p>
                            <p className="text-xs text-gray-500">{ride.destination}</p>
                          </div>
                          <span className="text-sm text-green-600">₹{ride.fare}</span>
                        </div>
                        <div className="flex justify-between items-center text-xs text-gray-500">
                          <span>{ride.date}</span>
                          <span>{ride.duration}</span>
                        </div>
                      </div>
                    ))}
                  </TabsContent>
                </Tabs>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      {/* Rating Dialog */}
      <RatingDialog
        open={ratingDialogOpen}
        onOpenChange={setRatingDialogOpen}
        targetName={selectedRiderForRating?.riderName || ''}
        targetRole="rider"
        onSubmit={handleRateRider}
      />
    </div>
  );
}