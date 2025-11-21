import { useState, useEffect } from 'react';
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
import { ProfilePictureUpload } from './ProfilePictureUpload';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './ui/tabs';
import { useLoadScript } from '@react-google-maps/api';

// Define libraries outside component to prevent re-renders
const libraries: ("places")[] = ["places"];

interface DriverDashboardProps {
  user: any;
}

export function DriverDashboard({ user }: DriverDashboardProps) {
  // Load Google Maps Script at the top level
  const { isLoaded, loadError } = useLoadScript({
    googleMapsApiKey: import.meta.env.VITE_APP_GOOGLE_MAPS_API_KEY || "",
    libraries,
  });

  const [activeRoute, setActiveRoute] = useState<any>(null);
  
  // FIX: Changed state to use 'lat' and 'lng' to match Google Maps requirements
  const [currentLocation, setCurrentLocation] = useState({ lat: 28.6139, lng: 77.2090 });
  
  const [trips, setTrips] = useState<any[]>([]);
  const [rideHistory, setRideHistory] = useState<any[]>([]);
  const [showRouteDialog, setShowRouteDialog] = useState(false);
  const [locationUpdateInterval, setLocationUpdateInterval] = useState<any>(null);
  const [driverRating, setDriverRating] = useState(4.7);
  const [totalEarnings, setTotalEarnings] = useState(0);
  const [profilePicture, setProfilePicture] = useState('');
  const [ratingDialogOpen, setRatingDialogOpen] = useState(false);
  const [selectedRiderForRating, setSelectedRiderForRating] = useState<any>(null);

  // Route registration form
  const [destination, setDestination] = useState('');
  const [destinationCoords, setDestinationCoords] = useState<any>(null);
  const [availableSeats, setAvailableSeats] = useState(4);

  useEffect(() => {
<<<<<<< HEAD
    // Load mock trips
    const mockTrips = [
      {
        id: 't1',
        riderId: 'r1',
        riderName: 'Sarah Johnson',
        riderRating: 4.8,
        pickupStation: 'Central Station',
        destination: 'Sector 62, Noida',
        status: 'scheduled',
        pickupTime: new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        fare: 150,
      },
      {
        id: 't2',
        riderId: 'r2',
        riderName: 'Mike Chen',
        riderRating: 4.5,
        pickupStation: 'Rajiv Chowk',
        destination: 'Greater Kailash',
        status: 'active',
        pickupTime: new Date(Date.now() - 900000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        fare: 120,
      },
    ];
    setTrips(mockTrips);

    // Load ride history
    const mockHistory = [
      {
        id: 'h1',
        date: '2024-11-18',
        riderName: 'Alex Kumar',
        destination: 'Dwarka Sector 10',
        fare: 180,
        rating: 5,
        duration: '25 min',
      },
      {
        id: 'h2',
        date: '2024-11-18',
        riderName: 'Priya Sharma',
        destination: 'Sarita Vihar',
        fare: 140,
        rating: 4,
        duration: '20 min',
      },
    ];
    setRideHistory(mockHistory);

    // Calculate total earnings
    const total = mockHistory.reduce((sum, ride) => sum + ride.fare, 0);
    setTotalEarnings(total);

    // Load profile picture from localStorage
=======
    // Load profile picture from localStorage (UI-only)
>>>>>>> efa8500968af7cd3c832948ed6130c9124cee04d
    const savedPicture = localStorage.getItem('profilePicture_' + user.name);
    if (savedPicture) setProfilePicture(savedPicture);

    // Get current location from browser
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          // FIX: Map browser coordinates to lat/lng
          setCurrentLocation({
            lat: position.coords.latitude,
            lng: position.coords.longitude,
          });
        },
        () => {
          toast.info('Using default location. Enable location services for accurate positioning.');
        }
      );
    }

    // Fetch dashboard from backend to hydrate state
    const fetchDashboard = async () => {
      try {
        const { data } = await driverApi.getDashboard(user.id);
        if (data?.success) {
          // Rating & earnings
          if (typeof data.driverRating === 'number') setDriverRating(data.driverRating);
          if (typeof data.totalEarnings === 'number') setTotalEarnings(data.totalEarnings);

          // Active trips -> trips state (match UI shape)
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

          // Ride history -> UI shape
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

          // Route meta
          if (data.destination) setDestination(data.destination);
          if (typeof data.availableSeats === 'number') setAvailableSeats(data.availableSeats);
          if (data.destination) setActiveRoute({
            id: 'route_from_backend',
            driverId: user.id,
            currentLocation: data.currentLocation ? { latitude: data.currentLocation.latitude, longitude: data.currentLocation.longitude } : currentLocation,
            destination: data.destination,
            destinationCoords: destinationCoords,
            availableSeats: data.availableSeats || availableSeats,
            departureTime: new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
          });
        }
      } catch (e) {
        // keep UI usable without backend data
      }
    };

    fetchDashboard();

    // Optional: refresh dashboard periodically
    const id = setInterval(fetchDashboard, 30000);
    return () => clearInterval(id);
  }, [user.id, user.name]);

  const handleRegisterRoute = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!destination) {
      toast.error('Please select a destination');
      return;
    }

    try {
      const currentTime = new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
      
<<<<<<< HEAD
=======
      // Persist route in backend
      await driverApi.registerRoute({
        driver_id: user.id,
        origin_station: '',
        destination,
        available_seats: availableSeats,
        metro_stations: [],
      });

>>>>>>> efa8500968af7cd3c832948ed6130c9124cee04d
      const route = {
        id: 'route_' + Date.now(),
        driverId: user.id,
        currentLocation: currentLocation,
        destination: destination,
        destinationCoords: destinationCoords,
        availableSeats,
        departureTime: currentTime,
      };

      setActiveRoute(route);
      setShowRouteDialog(false);
      toast.success('Route registered successfully! Starting location updates...');

      startLocationUpdates();
    } catch (error) {
      toast.error('Failed to register route');
    }
  };

  const startLocationUpdates = () => {
    if (locationUpdateInterval) {
      clearInterval(locationUpdateInterval);
    }

<<<<<<< HEAD
    const interval = setInterval(() => {
      // FIX: Update using lat/lng logic
=======
    const interval = setInterval(async () => {
      // Simulate movement
>>>>>>> efa8500968af7cd3c832948ed6130c9124cee04d
      setCurrentLocation(prev => ({
        lat: prev.lat + (Math.random() - 0.5) * 0.001,
        lng: prev.lng + (Math.random() - 0.5) * 0.001,
      }));
<<<<<<< HEAD
    }, 10000); 
=======

      // Persist to backend services
      try {
        const { latitude, longitude } = currentLocation;
        await driverApi.updateLocation(user.id, { driver_id: user.id, latitude, longitude });
        await locationApi.updateLocation(user.id, { latitude, longitude });
      } catch {
        // ignore transient errors
      }
    }, 10000); // 10s
>>>>>>> efa8500968af7cd3c832948ed6130c9124cee04d

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

  const handleProfilePictureUpload = (imageUrl: string) => {
    setProfilePicture(imageUrl);
  };

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
            <ProfilePictureUpload
              currentImage={profilePicture}
              onUpload={handleProfilePictureUpload}
              userName={user.name}
            />
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
                            {/* FIX: Display lat/lng */}
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
                <MapView
                  isLoaded={isLoaded}
                  currentLocation={currentLocation}
                  destination={activeRoute ? { ...destinationCoords, name: destination } : undefined}
                  showRoute={!!activeRoute}
                />
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