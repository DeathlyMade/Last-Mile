import { useState, useEffect, useRef } from 'react';
import { Button } from './ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from './ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';
import { MapPin, Clock, Car, Plus, CheckCircle2, XCircle, Loader2, Navigation, Star, Phone } from 'lucide-react';
import { toast } from 'sonner';
import { riderApi, matchingApi, stationApi, tripApi, driverApi, userApi } from '../lib/api';
import { MapView, PlaceSearchBox } from './MapView';
import { RatingDialog } from './RatingDialog';
import { useLoadScript } from '@react-google-maps/api';
import { matchingClient, locationClient, tripClient, getAuthMetadata } from '../lib/grpc';
import * as MatchingPb from '../proto/matching_pb.js';
import * as LocationPb from '../proto/location_pb.js';
import * as TripPb from '../proto/trip_pb.js';

// @ts-ignore
const MonitorMatchStatusRequest = MatchingPb.MonitorMatchStatusRequest || MatchingPb.default.MonitorMatchStatusRequest;
// @ts-ignore
const MonitorDriverLocationRequest = LocationPb.MonitorDriverLocationRequest || LocationPb.default.MonitorDriverLocationRequest;
// @ts-ignore
const MonitorTripUpdatesRequest = TripPb.MonitorTripUpdatesRequest || TripPb.default.MonitorTripUpdatesRequest;

// Define libraries outside component to prevent re-renders
const libraries: ("places")[] = ["places"];

interface RiderDashboardProps {
  user: any;
}

export function RiderDashboard({ user }: RiderDashboardProps) {
  const { isLoaded, loadError } = useLoadScript({
    googleMapsApiKey: import.meta.env.VITE_APP_GOOGLE_MAPS_API_KEY || "",
    libraries,
  });

  const [rides, setRides] = useState<any[]>([]);
  const [showRequestDialog, setShowRequestDialog] = useState(false);
  const [activeRide, setActiveRide] = useState<any>(null);
  const [ratingDialogOpen, setRatingDialogOpen] = useState(false);
  const [selectedDriverForRating, setSelectedDriverForRating] = useState<any>(null);
  // const [rideAccepted, setRideAccepted] = useState(false); // Removed as per request
  const [inRide, setInRide] = useState(false);
  const [driverLocation, setDriverLocation] = useState<{ latitude: number, longitude: number } | undefined>(undefined);

  // Ride request form
  const [metroStation, setMetroStation] = useState('');
  const [destination, setDestination] = useState('');
  const [destinationCoords, setDestinationCoords] = useState<any>(null);
  const [arrivalTime, setArrivalTime] = useState('');
  const [matching, setMatching] = useState(false);
  const [stations, setStations] = useState<any[]>([]);

  // Loading states
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  // Stream references to cancel on unmount
  const matchStreamRef = useRef<any>(null);
  const locationStreamRef = useRef<any>(null);
  const tripStreamRef = useRef<any>(null);


  // Format arrival time to IST-friendly display.
  const formatArrivalTimeIST = (value: any) => {
    if (value === null || value === undefined) return '-';
    const isNumericString = typeof value === 'string' && /^\d+$/.test(value);
    const epoch = typeof value === 'number' ? value : (isNumericString ? Number(value) : NaN);
    if (Number.isFinite(epoch)) {
      const d = new Date(epoch * 1000);
      return d.toLocaleTimeString('en-IN', {
        timeZone: 'Asia/Kolkata',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      });
    }
    // If it's already a formatted string like HH:mm, show as-is
    return String(value);
  };

  // Backend persistence: fetch rides for this rider on mount
  useEffect(() => {
    // Load stations from backend
    stationApi.getAllStations()
      .then(({ data }) => {
        const items = Array.isArray(data?.stations) ? data.stations.map((s: any) => ({
          id: s.station_id,
          name: s.name,
          metroLine: s.line,
        })) : [];
        setStations(items);
      })
      .catch(() => setStations([]));

    // Fetch dashboard data (current ride + history)
    riderApi.getDashboard(user.id)
      .then(({ data }) => {
        if (data?.success) {
          const history = Array.isArray(data.rideHistory) ? data.rideHistory : [];
          const current = data.currentRide;

          const mapRide = (r: any) => ({
            id: r.rideRequestId || r.ride_request_id,
            riderId: r.riderId || r.rider_id,
            metroStation: r.metroStation || r.metro_station,
            destination: r.destination,
            destinationCoords: undefined,
            arrivalTime: r.arrivalTime || r.arrival_time,
            status: (r.status === 0 || r.status === 'PENDING') ? 'pending' :
              (r.status === 1 || r.status === 'MATCHED') ? 'matched' :
                (r.status === 2 || r.status === 'IN_PROGRESS') ? 'active' :
                  (r.status === 3 || r.status === 'COMPLETED') ? 'completed' :
                    (r.status === 4 || r.status === 'CANCELLED') ? 'cancelled' : 'pending',
            fare: r.fare || (Math.floor(Math.random() * 100) + 100),
            tripId: r.tripId || r.trip_id,
            driver: r.driverId ? {
              id: r.driverId,
              name: r.driverName || r.driver_name || 'Driver',
              rating: 5.0
            } : undefined
          });

          const allRides = history.map(mapRide);
          if (current) {
            const active = mapRide(current);
            allRides.unshift(active);
            setActiveRide(active);

            // If matched/active, restore driver info if possible
            if (active.driver && active.driver.id) {
              // Fetch driver name from User Service
              userApi.getProfile(active.driver.id).then(userRes => {
                if (userRes.data?.success) {
                  const driverName = userRes.data.name;
                  const driverPhone = userRes.data.phone;

                  console.log("Driver Name:", driverName);
                  console.log("Driver Phone:", driverPhone);

                  // Also try to get rating/location from Driver Service
                  driverApi.getDashboard(active.driver.id).then(driverRes => {
                    const driverData = driverRes.data || {};
                    setActiveRide((prev: any) => ({
                      ...prev,
                      driver: {
                        ...prev.driver,
                        name: driverName,
                        phone: driverPhone,
                        rating: driverData.rating || 4.8,
                        currentLocation: driverData.currentLocation,
                        vehicle: 'Toyota Prius' // Hardcoded for now as per previous code
                      }
                    }));
                  }).catch(() => {
                    // If driver service fails, at least we have the name
                    setActiveRide((prev: any) => ({
                      ...prev,
                      driver: {
                        ...prev.driver,
                        name: driverName,
                        phone: driverPhone
                      }
                    }));
                  });
                }
              }).catch(err => console.error("Failed to fetch driver profile", err));
            }
          }
          setRides(allRides);
        }
      })
      .catch((err) => console.error("Failed to load dashboard", err));
  }, [user.id]);

  // Clean up streams on unmount
  useEffect(() => {
    return () => {
      if (matchStreamRef.current) matchStreamRef.current.cancel();
      if (locationStreamRef.current) locationStreamRef.current.cancel();
      if (tripStreamRef.current) tripStreamRef.current.cancel();
    };
  }, []);

  // Monitor Driver Location when active ride has a driver
  useEffect(() => {
    if (activeRide?.driver?.id && (activeRide.status === 'matched' || activeRide.status === 'active')) {
      if (locationStreamRef.current) locationStreamRef.current.cancel();

      const req = new MonitorDriverLocationRequest();
      req.setDriverId(activeRide.driver.id);

      console.log("📡 Connecting to Location Stream for driver:", activeRide.driver.id);
      const stream = locationClient.monitorDriverLocation(req, getAuthMetadata());
      locationStreamRef.current = stream;

      stream.on('data', (response: any) => {
        const lat = response.getLatitude();
        const lng = response.getLongitude();
        console.log("📍 Driver Location Update:", lat, lng);
        setDriverLocation({ latitude: lat, longitude: lng });

        // Update active ride driver location
        setActiveRide((prev: any) => {
          if (!prev) return prev;
          return {
            ...prev,
            driver: {
              ...prev.driver,
              currentLocation: { latitude: lat, longitude: lng }
            }
          };
        });
      });

      stream.on('error', (err: any) => {
        console.error('Location stream error', err);
      });
    } else {
      if (locationStreamRef.current) {
        locationStreamRef.current.cancel();
        locationStreamRef.current = null;
      }
    }
  }, [activeRide?.driver?.id, activeRide?.status]);

  // Keep a ref to activeRide to avoid stale closures in stream listeners
  const activeRideRef = useRef(activeRide);
  useEffect(() => {
    activeRideRef.current = activeRide;
  }, [activeRide]);

  // Monitor Trip Updates
  useEffect(() => {
    if (activeRide?.tripId) {
      if (tripStreamRef.current) tripStreamRef.current.cancel();

      const req = new MonitorTripUpdatesRequest();
      req.setTripId(activeRide.tripId);

      const stream = tripClient.monitorTripUpdates(req, getAuthMetadata());
      tripStreamRef.current = stream;

      stream.on('data', (response: any) => {
        const status = response.getStatus(); // Enum or string
        // Map proto status to frontend status
        let newStatus = activeRideRef.current?.status; // Use ref here
        if (status === 1 || status === 'ACTIVE') newStatus = 'active';
        if (status === 2 || status === 'COMPLETED') newStatus = 'completed';

        if (newStatus && newStatus !== activeRideRef.current?.status) {
          setActiveRide((prev: any) => ({ ...prev, status: newStatus }));
          if (newStatus === 'completed') {
            handleCompleteRide();
          }
        }
      });
    }
  }, [activeRide?.tripId]);

  const handleRequestRide = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!destination || !destinationCoords) {
      toast.error('Please select a destination from the search');
      return;
    }

    setMatching(true);

    try {
      // 1) Compute arrival time
      const arrivalEpoch = (() => {
        if (!arrivalTime) return Math.floor(Date.now() / 1000);
        try {
          const [hh, mm] = arrivalTime.split(':');
          const d = new Date();
          d.setSeconds(0, 0);
          d.setHours(Number(hh), Number(mm));
          const t = d.getTime();
          if (Number.isNaN(t)) return Math.floor(Date.now() / 1000);
          return Math.floor(t / 1000); // seconds
        } catch {
          return Math.floor(Date.now() / 1000);
        }
      })();

      // Register Request (REST)
      const reg = await riderApi.registerRideRequest(user.id, {
        riderId: user.id,
        metroStation,
        destination,
        arrivalTime: arrivalEpoch,
      });
      const respData = reg?.data || {};
      const ride_request_id = respData.ride_request_id || respData.rideRequestId;

      if (!ride_request_id) throw new Error('No ride_request_id');

      const pendingRide = {
        id: ride_request_id,
        riderId: user.id,
        metroStation,
        destination,
        destinationCoords,
        arrivalTime,
        status: 'pending',
        fare: Math.floor(Math.random() * 100) + 100,
      };
      setRides(prev => ([pendingRide, ...prev]));
      setActiveRide(pendingRide);
      setShowRequestDialog(false);
      toast.success('Ride request submitted! Matching with nearby drivers...');

      // 2) Request matching (REST)
      // Note: We still use the REST API to trigger the match, but we listen via gRPC
      await matchingApi.matchRiderWithDriver({
        riderId: user.id,
        rideRequestId: ride_request_id, // Added missing field
        metroStation,
        destination,
        arrivalTime: arrivalEpoch,
      });

      // 3) Start Streaming Match Status (gRPC)
      if (matchStreamRef.current) matchStreamRef.current.cancel();

      const req = new MonitorMatchStatusRequest();
      req.setRiderId(user.id);

      const stream = matchingClient.monitorMatchStatus(req, getAuthMetadata());
      matchStreamRef.current = stream;

      stream.on('data', async (response: any) => {
        const status = response.getStatus(); // Enum or string
        const matchId = response.getMatchId();
        const driverId = response.getDriverId();
        const tripId = response.getTripId();
        // @ts-ignore
        const fare = response.getFare ? response.getFare() : 0;

        console.log('DEBUG: Match Update Received:', { status, matchId, driverId, tripId, fare });

        // Check for CONFIRMED status (Enum value 2 or string "CONFIRMED")
        if (status === 2 || status === 'CONFIRMED') {
          // Match confirmed!
          // Match confirmed!
          let driverInfo: any = null;
          let driverName = `Driver ${driverId.substring(0, 5)}`;
          let driverPhone = '9999999999';

          try {
            // Fetch real driver name from User Service
            const userRes = await userApi.getProfile(driverId);
            if (userRes.data?.success) {
              driverName = userRes.data.name;
              driverPhone = userRes.data.phone;
            }
            // Fetch driver location/rating
            driverInfo = (await driverApi.getDashboard(driverId)).data;
          } catch { }

          // Use functional update to ensure we have latest state
          setRides(prev => {
            console.log("DEBUG: Updating rides. Current count:", prev.length);
            // Match strictly by ID (rideRequestId should equal matchId)
            return prev.map(r => {
              if (r.id === matchId) {
                console.log("DEBUG: Found ride to update:", r.id);
                const matchedRide = {
                  ...r,
                  status: 'matched',
                  tripId,
                  fare: fare > 0 ? fare : r.fare, // Update fare if provided
                  driver: {
                    id: driverId,
                    name: driverName,
                    vehicle: 'Toyota Prius',
                    rating: driverInfo?.rating || 4.8,
                    phone: driverPhone,
                    currentLocation: driverInfo?.currentLocation ? {
                      latitude: driverInfo.currentLocation.latitude,
                      longitude: driverInfo.currentLocation.longitude,
                    } : undefined,
                  },
                };
                // Also update activeRide if it matches
                setActiveRide(matchedRide);
                return matchedRide;
              }
              return r;
            });
          });

          toast.success('Match confirmed! Your driver is on the way.');
          stream.cancel();

        } else if (status === 3 || status === 'CANCELLED') {
          setRides(prev => prev.map(r => r.id === matchId ? { ...r, status: 'cancelled' } : r));
          if (activeRide?.id === matchId) setActiveRide(null);
          toast.error('Ride request was cancelled.');
          stream.cancel();
        }
      });

      stream.on('error', (err: any) => {
        console.error('Match stream error', err);
        // Don't toast error immediately as it might be just a connection drop
      });

    } catch (error: any) {
      console.error('Ride request error', error);
      toast.error('Failed to request ride');
    } finally {
      setMatching(false);
    }
  };


  // Removed handleAcceptRide as the user wants auto-acceptance logic
  // const handleAcceptRide = () => {
  //   setRideAccepted(true);
  //   toast.success('Ride accepted! You can track your driver on the map.');
  // };

  const handleEnterRide = async () => {
    setInRide(true);
    if (activeRide) {
      try {
        if (activeRide.tripId) {
          await tripApi.recordPickup(activeRide.tripId, { latitude: 0, longitude: 0 });
        }
      } catch { }
      setActiveRide((prev: any) => ({ ...prev, status: 'active' }));
      toast.success('Ride started! Navigating to your destination.');
    }
  };

  const handleCompleteRide = async () => {
    // Prevent loop if already completed or dialog open
    if (ratingDialogOpen || activeRide?.status === 'completed') return;

    if (activeRide?.driver) {
      try {
        if (activeRide.tripId) {
          await tripApi.recordDropoff(activeRide.tripId, { latitude: 0, longitude: 0 });
        }
      } catch { }
      setSelectedDriverForRating(activeRide.driver);
      setRatingDialogOpen(true);
      setActiveRide((prev: any) => ({ ...prev, status: 'completed' }));
      // setRideAccepted(false);
      setInRide(false);
      toast.success('Ride completed! Please rate your driver.');
    }
  };

  const handleRateDriver = (rating: number, feedback: string) => {
    toast.success(`Thank you for rating ${selectedDriverForRating?.name}!`);
    // In production, send rating to backend

    // Reset state to allow new ride
    setRatingDialogOpen(false);
    setSelectedDriverForRating(null);
    setActiveRide(null);
    setInRide(false);
  };

  const handleCancelRide = async (rideId: string) => {
    setCancellingId(rideId);
    try {
      await matchingApi.cancelMatch(rideId, {
        matchId: rideId,
        riderId: user.id
      });

      setRides(prev => prev.filter((ride: any) => ride.id !== rideId));
      if (activeRide?.id === rideId) {
        setActiveRide(null);
        // setRideAccepted(false);
        setInRide(false);
      }
      toast.success('Ride request cancelled successfully');
    } catch (error: any) {
      console.error("Cancel error:", error);
      toast.error('Failed to cancel ride');
    } finally {
      setCancellingId(null);
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'matched': return 'bg-green-500';
      case 'active': return 'bg-blue-500';
      case 'completed': return 'bg-gray-500';
      case 'cancelled': return 'bg-red-500';
      default: return 'bg-yellow-500';
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-8">
          <h1 className="text-3xl mb-2">Rider Dashboard</h1>
          <p className="text-gray-600">Book and track your rides</p>
        </div>

        {/* Quick Booking Panel (inline instead of dialog) */}
        <Card className="mb-8">
          <CardHeader>
            <CardTitle>Book a Ride</CardTitle>
            <CardDescription>Select pickup station and destination</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleRequestRide} className="grid grid-cols-1 md:grid-cols-3 gap-6 items-end">
              <div className="space-y-2">
                <Label htmlFor="station">Pickup Station</Label>
                <Select value={metroStation} onValueChange={setMetroStation} required>
                  <SelectTrigger>
                    <SelectValue placeholder="Select station" />
                  </SelectTrigger>
                  <SelectContent>
                    {stations.map((station) => (
                      <SelectItem key={station.id} value={station.name}>
                        {station.name} ({station.metroLine})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="destination">Destination</Label>
                {isLoaded ? (
                  <PlaceSearchBox
                    placeholder="Search destination..."
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
                <Label htmlFor="arrival">Arrival Time (at station)</Label>
                <Input
                  id="arrival"
                  type="time"
                  value={arrivalTime}
                  onChange={(e) => setArrivalTime(e.target.value)}
                  required
                />
                <Button type="submit" disabled={matching || !metroStation || !destination || !!activeRide} className="w-full mt-2">
                  {matching ? (
                    <><Loader2 className="h-4 w-4 mr-2 animate-spin" />Matching...</>
                  ) : activeRide ? (
                    'Ride in Progress'
                  ) : (
                    'Request Ride'
                  )}
                </Button>
                {activeRide && (
                  <p className="text-xs text-amber-600 mt-2 text-center">
                    You have an active ride. Please complete or cancel it to request a new one.
                  </p>
                )}
              </div>
            </form>
            {matching && (
              <p className="text-xs text-blue-600 mt-3">Attempting to find a driver for your route...</p>
            )}
          </CardContent>
        </Card>

        {/* Stats Cards */}
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Active Rides</p>
                  <p className="text-2xl mt-1">
                    {rides.filter(r => r.status === 'matched' || r.status === 'active').length}
                  </p>
                </div>
                <Car className="h-8 w-8 text-blue-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Pending</p>
                  <p className="text-2xl mt-1">{rides.filter(r => r.status === 'pending').length}</p>
                </div>
                <Clock className="h-8 w-8 text-yellow-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Completed</p>
                  <p className="text-2xl mt-1">{rides.filter(r => r.status === 'completed').length}</p>
                </div>
                <CheckCircle2 className="h-8 w-8 text-green-500" />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-600">Total Rides</p>
                  <p className="text-2xl mt-1">{rides.length}</p>
                </div>
                <Navigation className="h-8 w-8 text-purple-500" />
              </div>
            </CardContent>
          </Card>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Active Ride & Map */}
          <div className="lg:col-span-2 space-y-6">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div>
                    <CardTitle>Current Ride</CardTitle>
                    <CardDescription>Your active or upcoming ride</CardDescription>
                  </div>
                  {/* Dialog removed: inline booking panel above */}
                </div>
              </CardHeader>
              <CardContent>
                {activeRide ? (
                  <div className="space-y-4">
                    <div className="flex items-center justify-between mb-4">
                      <Badge className={getStatusColor(activeRide.status)}>
                        {activeRide.status.toUpperCase()}
                      </Badge>
                      <span className="text-sm text-gray-600">Fare: ₹{activeRide.fare}</span>
                    </div>

                    {activeRide.driver && (
                      <div className="bg-gradient-to-br from-blue-50 to-purple-50 border-2 border-blue-200 rounded-lg p-4 mb-4">
                        <p className="text-sm text-blue-900 mb-3">Your Driver</p>
                        <div className="flex items-center gap-3 mb-3">
                          <div className="w-14 h-14 bg-gradient-to-br from-blue-500 to-purple-500 rounded-full flex items-center justify-center text-white text-xl">
                            {activeRide.driver.name.charAt(0)}
                          </div>
                          <div className="flex-1">
                            <p>{activeRide.driver.name}</p>
                            <p className="text-sm text-gray-600">{activeRide.driver.vehicle}</p>
                            <div className="flex items-center gap-1 mt-1">
                              <Star className="h-4 w-4 fill-yellow-400 text-yellow-400" />
                              <span className="text-sm">{activeRide.driver.rating}</span>
                            </div>
                          </div>
                        </div>
                        <a
                          href={`tel:${activeRide.driver.phone}`}
                          className="flex items-center justify-center gap-2 bg-white text-blue-600 py-2 px-4 rounded-lg hover:bg-blue-50 transition-colors"
                        >
                          <Phone className="h-4 w-4" />
                          <span className="text-sm">{activeRide.driver.phone}</span>
                        </a>
                      </div>
                    )}

                    <div className="space-y-3">
                      <div className="flex items-start gap-3">
                        <MapPin className="h-5 w-5 text-green-500 mt-1" />
                        <div className="flex-1">
                          <p className="text-sm text-gray-600">Pickup</p>
                          <p>{activeRide.metroStation}</p>
                        </div>
                      </div>
                      <div className="flex items-start gap-3">
                        <MapPin className="h-5 w-5 text-red-500 mt-1" />
                        <div className="flex-1">
                          <p className="text-sm text-gray-600">Destination</p>
                          <p>{activeRide.destination}</p>
                        </div>
                      </div>
                    </div>

                    {/* Action buttons */}
                    <div className="space-y-2 mt-4">
                      {/* Lifecycle buttons removed - Status driven by Driver Dashboard & Stream */}

                      {activeRide.status === 'matched' && (
                        <div className="bg-blue-50 border border-blue-200 rounded-md p-3 text-center">
                          <p className="text-sm text-blue-800 font-medium">
                            Ride matched with a driver.
                          </p>
                          <p className="text-xs text-blue-600 mt-1">
                            Cancellation is not available at this stage.
                          </p>
                        </div>
                      )}

                      {activeRide.status !== 'completed' && activeRide.status !== 'cancelled' && activeRide.status !== 'active' && activeRide.status !== 'matched' && (
                        <Button
                          variant="destructive"
                          className="w-full"
                          onClick={() => handleCancelRide(activeRide.id)}
                          disabled={cancellingId === activeRide.id}
                        >
                          {cancellingId === activeRide.id ? (
                            <><Loader2 className="h-4 w-4 mr-2 animate-spin" /> Cancelling...</>
                          ) : (
                            <><XCircle className="h-4 w-4 mr-2" /> Cancel Ride</>
                          )}
                        </Button>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="text-center py-8 text-gray-500">
                    <Car className="h-12 w-12 mx-auto mb-3 text-gray-300" />
                    <p>No active rides</p>
                    <p className="text-sm">Request a ride to get started</p>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Pending Requests */}
            <Card>
              <CardHeader>
                <CardTitle>Pending Requests</CardTitle>
                <CardDescription>Awaiting driver match</CardDescription>
              </CardHeader>
              <CardContent>
                {rides.filter(r => r.status === 'pending').length === 0 ? (
                  <p className="text-sm text-gray-500">No pending requests</p>
                ) : (
                  <div className="space-y-3">
                    {rides.filter(r => r.status === 'pending').map((ride) => (
                      <div key={ride.id} className="border rounded-lg p-3 flex items-center justify-between">
                        <div className="min-w-0">
                          <p className="truncate"><span className="text-gray-600">Pickup:</span> {ride.metroStation}</p>
                          <p className="truncate"><span className="text-gray-600">Destination:</span> {ride.destination}</p>
                          <p className="text-xs text-gray-500">ETA at station: {formatArrivalTimeIST(ride.arrivalTime)}</p>
                        </div>
                        <div className="flex items-center gap-2 ml-4">
                          <Badge className="bg-yellow-500">PENDING</Badge>
                          <Button
                            variant="destructive"
                            size="sm"
                            onClick={() => handleCancelRide(ride.id)}
                            disabled={cancellingId === ride.id}
                          >
                            {cancellingId === ride.id ? <Loader2 className="h-4 w-4 animate-spin" /> : "Cancel"}
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Map View - Always show if matched or active */}
            {activeRide && (activeRide.status === 'matched' || activeRide.status === 'active') && (
              <Card>
                <CardHeader>
                  <CardTitle>Live Tracking</CardTitle>
                  <CardDescription>
                    {inRide ? 'Navigating to your destination' : 'Driver is on the way to pick you up'}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="h-[400px]">
                    <MapView
                      isLoaded={isLoaded}
                      currentLocation={activeRide.driver?.currentLocation}
                      destination={activeRide.destinationCoords ? { ...activeRide.destinationCoords, name: activeRide.destination } : undefined}
                      showRoute={true}
                    />
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Ride History */}
            <Card>
              <CardHeader>
                <CardTitle>Ride History</CardTitle>
                <CardDescription>Your past and pending rides</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {rides.map((ride) => (
                    <div key={ride.id} className="border rounded-lg p-4">
                      <div className="flex items-start justify-between mb-3">
                        <div>
                          <p>{ride.metroStation}</p>
                          <p className="text-sm text-gray-600">{ride.destination}</p>
                        </div>
                        <Badge className={getStatusColor(ride.status)}>
                          {ride.status}
                        </Badge>
                      </div>
                      <div className="flex items-center justify-between text-sm text-gray-600">
                        <div className="flex items-center gap-2">
                          <Clock className="h-4 w-4" />
                          <span>{formatArrivalTimeIST(ride.arrivalTime)}</span>
                        </div>
                        {ride.fare && <span>₹{ride.fare}</span>}
                      </div>
                      {ride.driver && (
                        <div className="mt-3 pt-3 border-t">
                          <p className="text-sm text-gray-600">
                            Driver: {ride.driver.name}
                            <Star className="h-3 w-3 inline ml-2 fill-yellow-400 text-yellow-400" />
                            <span className="text-xs ml-1">{ride.driver.rating}</span>
                          </p>
                        </div>
                      )}
                    </div>
                  ))}
                  {rides.length === 0 && (
                    <div className="text-center py-8 text-gray-500">
                      <Clock className="h-12 w-12 mx-auto mb-3 text-gray-300" />
                      <p>No rides yet</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* Quick Stats */}
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>Quick Stats</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Total Rides</span>
                  <span>{rides.length}</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Completed</span>
                  <span className="text-green-600">{rides.filter(r => r.status === 'completed').length}</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Active</span>
                  <span className="text-blue-600">
                    {rides.filter(r => r.status === 'matched' || r.status === 'active').length}
                  </span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-sm text-gray-600">Cancelled</span>
                  <span className="text-red-600">{rides.filter(r => r.status === 'cancelled').length}</span>
                </div>
                <div className="border-t pt-3 mt-3">
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Total Spent</span>
                    <span>₹{rides.filter(r => r.status === 'completed').reduce((sum, r) => sum + (r.fare || 0), 0)}</span>
                  </div>
                </div>
              </CardContent>
            </Card>

            {activeRide && activeRide.driver && (
              <Card>
                <CardHeader>
                  <CardTitle>Trip Info</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Status</span>
                    <Badge className={getStatusColor(activeRide.status)} variant="outline">
                      {activeRide.status}
                    </Badge>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Fare</span>
                    <span>₹{activeRide.fare}</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">ETA</span>
                    <span>~15 min</span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-sm text-gray-600">Distance</span>
                    <span>~8.5 km</span>
                  </div>
                </CardContent>
              </Card>
            )}

            <Card className="bg-gradient-to-br from-blue-50 to-purple-50">
              <CardHeader>
                <CardTitle>Need Help?</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                <Button variant="outline" className="w-full justify-start bg-white">
                  <Phone className="h-4 w-4 mr-2" />
                  Support
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      <RatingDialog
        open={ratingDialogOpen}
        onOpenChange={setRatingDialogOpen}
        targetName={selectedDriverForRating?.name || ''}
        targetRole="driver"
        onSubmit={handleRateDriver}
      />
    </div>
  );
}
