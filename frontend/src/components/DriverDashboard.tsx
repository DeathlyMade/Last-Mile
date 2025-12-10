import { useState, useEffect, useRef, useCallback } from 'react';
import { Button } from './ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger, DialogFooter } from './ui/dialog';
import { MapPin, Users, CheckCircle2, Plus, Activity, DollarSign, Star, Clock, XCircle, Car } from 'lucide-react';
import { toast } from 'sonner';
import { MapView, PlaceSearchBox } from './MapView';
import { RatingDialog } from './RatingDialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './ui/tabs';
import { useLoadScript } from '@react-google-maps/api';

// API & gRPC Imports
import { driverApi, locationApi, tripApi, stationApi, matchingApi } from '../lib/api';
import { driverClient } from '../lib/grpc';
import * as DriverPb from '../proto/driver_pb.js';

// @ts-ignore
const MonitorDriverDashboardRequest = DriverPb.MonitorDriverDashboardRequest || DriverPb.default.MonitorDriverDashboardRequest;

const libraries: ("places")[] = ["places"];

// Helper for Auth Headers in gRPC
const getAuthMetadata = () => {
  const token = localStorage.getItem('auth_token');
  return token ? { 'Authorization': `Bearer ${token}` } : {};
};

interface DriverDashboardProps {
  user: any;
}

export function DriverDashboard({ user }: DriverDashboardProps) {
  const ROUTE_STORAGE_KEY = `driver_active_route_${user.id}`;

  // Google Maps Load
  const { isLoaded, loadError } = useLoadScript({
    googleMapsApiKey: import.meta.env.VITE_APP_GOOGLE_MAPS_API_KEY || "",
    libraries,
  });

  // --- State Management ---
  const [activeRoute, setActiveRoute] = useState<any>(null);
  const [currentLocation, setCurrentLocation] = useState({ lat: 28.6139, lng: 77.2090 });

  // Dashboard Data
  const [trips, setTrips] = useState<any[]>([]);
  const [rideHistory, setRideHistory] = useState<any[]>([]);
  const [driverRating, setDriverRating] = useState(5.0);
  const [totalEarnings, setTotalEarnings] = useState(0);

  // UI State
  const [showRouteDialog, setShowRouteDialog] = useState(false);
  const [ratingDialogOpen, setRatingDialogOpen] = useState(false);
  const [selectedRiderForRating, setSelectedRiderForRating] = useState<any>(null);

  // Incoming Match State
  const [incomingMatch, setIncomingMatch] = useState<any>(null);

  // Route Form State
  const [destination, setDestination] = useState('');
  const [destinationCoords, setDestinationCoords] = useState<any>(null);
  const [availableSeats, setAvailableSeats] = useState(4);

  // Refs for Cleanup
  const streamRef = useRef<any>(null);
  const lastUpdateRef = useRef<number>(0);
  const [locationUpdateInterval, setLocationUpdateInterval] = useState<any>(null);
  const [isLocationResolved, setIsLocationResolved] = useState(false);

  // --- 1. Initial Data Load (REST) ---
  // We only do this ONCE on mount to populate history/earnings. 
  // --- 1. Initial Data Load (REST) ---
  const loadInitialData = useCallback(async () => {
    try {
      // Restore route from local storage if exists
      const storedRouteRaw = localStorage.getItem(ROUTE_STORAGE_KEY);
      let storedRoute = storedRouteRaw ? JSON.parse(storedRouteRaw) : null;
      if (storedRoute) {
        setActiveRoute(storedRoute);
      }

      // Fetch baseline data
      const { data } = await driverApi.getDashboard(user.id);
      if (data?.success) {
        setDriverRating(data.driverRating || 5.0);
        setTotalEarnings(data.totalEarnings || 0);

        // Set History
        const history = Array.isArray(data.rideHistory) ? data.rideHistory.map((r: any) => ({
          id: r.tripId,
          date: r.date,
          riderName: r.riderName,
          destination: r.destination,
          fare: r.fare,
          rating: r.ratingGiven,
          duration: '—',
        })) : [];
        setRideHistory(history);

        // Set Active Route if backend has one
        if (data.destination) {
          let destCoords = storedRoute?.destinationCoords;

          // If we don't have coords (e.g. cleared storage), try to geocode
          if (!destCoords && window.google && isLoaded) {
            try {
              const geocoder = new window.google.maps.Geocoder();
              const result = await geocoder.geocode({ address: data.destination });
              if (result.results[0]) {
                const loc = result.results[0].geometry.location;
                destCoords = { lat: loc.lat(), lng: loc.lng() };
              }
            } catch (e) {
              console.error("Failed to geocode destination on reload", e);
            }
          }

          const routeData = {
            id: 'route_backend',
            driverId: user.id,
            destination: data.destination,
            availableSeats: data.availableSeats,
            destinationCoords: destCoords,
          };

          // Only update if we have coords, otherwise MapView won't work anyway
          if (destCoords) {
            setActiveRoute(routeData);
            setDestination(data.destination);
            setDestinationCoords(destCoords);
            // Update storage so next time it's faster
            localStorage.setItem(ROUTE_STORAGE_KEY, JSON.stringify(routeData));
            startLocationUpdates();
          }
        } else {
          // Backend has no route, so clear local if any (sync)
          if (storedRoute) {
            setActiveRoute(null);
            localStorage.removeItem(ROUTE_STORAGE_KEY);
          }
        }
        setTrips(data.activeTrips || []);
      }
    } catch (error) {
      console.error("DEBUG: Failed to reload dashboard data", error);
    }
  }, [user.id, ROUTE_STORAGE_KEY, isLoaded]);

  useEffect(() => {
    // Geolocation Init
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          setCurrentLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude });
          setIsLocationResolved(true);
          loadInitialData().catch((err) => console.error("Failed to load dashboard", err));
        },
        () => {
          setIsLocationResolved(true); // Fallback to default
          loadInitialData().catch((err) => console.error("Failed to load dashboard", err));
        }
      );
    } else {
      setIsLocationResolved(true);
      loadInitialData().catch((err) => console.error("Failed to load dashboard", err));
    }
  }, [user.id, loadInitialData]);

  // --- 2. Server-Side Streaming (gRPC) ---
  // Listens for updates (New Rides, Trip Status). No polling.
  useEffect(() => {
    if (!user.id) return;

    // Cancel existing stream if any
    if (streamRef.current) {
      streamRef.current.cancel();
    }

    const request = new MonitorDriverDashboardRequest();
    request.setDriverId(user.id);

    console.log("📡 Connecting to Driver Stream...");
    // FIX: Passing Auth Metadata here
    const stream = driverClient.monitorDriverDashboard(request, getAuthMetadata());
    streamRef.current = stream;

    stream.on('data', (response: any) => {
      // A. Handle New Ride Requests
      const matchRequest = response.getMatchRequest();
      if (matchRequest) {
        const matchData = {
          matchId: matchRequest.getMatchId(),
          riderId: matchRequest.getRiderId(),
          pickupStation: matchRequest.getPickupStation(),
          destination: matchRequest.getDestination(),
          fare: matchRequest.getFare(),
        };
        console.log("New Match Request:", matchData);
        setIncomingMatch(matchData);
        toast.info(`New Ride Request!`, {
          description: `Fare: ₹${matchData.fare} to ${matchData.destination}`
        });
      }

      // B. Handle Trip Status Updates
      const tripUpdate = response.getTripUpdate();
      if (tripUpdate) {
        const tripId = tripUpdate.getTripId();
        const status = tripUpdate.getStatus();
        console.log(`Trip Update: ${tripId} -> ${status}`);
        // No need to manually update state here if we rely on the activeTrips list below,
        // but we can keep it for immediate feedback or toast notifications.
      }

      // C. Handle Active Trips List Refresh (Primary Source of Truth)
      const activeTripsList = response.getActiveTripsList();
      if (activeTripsList) {
        // console.log("DEBUG: Received active trips from stream:", activeTripsList.length);
        const active = activeTripsList.map((t: any) => ({
          id: t.getTripId(),
          riderId: t.getRiderId(),
          riderName: t.getRiderName(),
          riderRating: t.getRiderRating(),
          pickupStation: t.getPickupStation(),
          destination: t.getDestination(),
          status: t.getStatus(),
          pickupTime: t.getPickupTimestamp(),
          fare: t.getFare(),
        }));

        // Prevent unnecessary re-renders (fixes shaking) & Throttle updates (15s)
        setTrips(prev => {
          const now = Date.now();
          const timeDiff = now - lastUpdateRef.current;

          // Always update if list length changes (new trip or completed trip)
          if (prev.length !== active.length) {
            lastUpdateRef.current = now;
            return active;
          }

          // Otherwise, only update every 15 seconds to prevent visual jitter
          if (timeDiff > 15000) {
            // Check if content actually changed to avoid even 15s re-renders if data is same
            if (JSON.stringify(prev) !== JSON.stringify(active)) {
              lastUpdateRef.current = now;
              return active;
            }
          }

          // If we have a status change (e.g. scheduled -> active), we MUST update immediately
          const statusChanged = prev.some((p, i) => active[i] && p.status !== active[i].status);
          if (statusChanged) {
            lastUpdateRef.current = now;
            return active;
          }

          return prev;
        });
      }

      // D. Handle Driver Status Updates (Available Seats, Earnings, History) via Message Field
      const msg = response.getMessage();
      if (msg && msg.startsWith('{')) {
        try {
          const data = JSON.parse(msg);

          // 1. Available Seats
          if (data.availableSeats !== undefined) {
            setActiveRoute((prev: any) => {
              if (!prev) return { availableSeats: data.availableSeats };
              if (prev.availableSeats === data.availableSeats) return prev;
              return { ...prev, availableSeats: data.availableSeats };
            });
            setAvailableSeats(data.availableSeats);
          }

          // 2. Total Earnings
          if (data.totalEarnings !== undefined) {
            setTotalEarnings(data.totalEarnings);
          }

          // 3. Latest Trip History (for Dropoff)
          if (data.latestTrip) {
            const newHistoryItem = {
              id: data.latestTrip.id,
              date: data.latestTrip.date,
              riderName: data.latestTrip.riderName,
              destination: data.latestTrip.destination,
              fare: data.latestTrip.fare,
              rating: 0, // Pending
              duration: 'Just now'
            };
            setRideHistory(prev => {
              // Avoid duplicates
              if (prev.some(h => h.id === newHistoryItem.id)) return prev;
              return [newHistoryItem, ...prev];
            });

            // Trigger Rating Dialog if we just completed this trip
            // We can infer this if the trip was recently in our active list
            // But for now, let's rely on the user flow or just show it if we have a new history item
            // Actually, handleDropoff sets selectedRiderForRating, so we just need to open the dialog
            // We can do that here if we want, but handleDropoff already does it.
            // However, handleDropoff might have cleared the trip from active list already?
            // No, handleDropoff calls API, then stream updates.
            // Let's keep the dialog opening in handleDropoff for now as it's UI interaction.
          }

        } catch (e) {
          // console.error("Failed to parse message JSON", e);
        }
      }
    });

    stream.on('error', (err: any) => {
      if (err.code !== 1) { // Ignore cancelled errors
        console.error('Stream error:', err);
      }
    });

    return () => {
      if (streamRef.current) streamRef.current.cancel();
    };
  }, [user.id]);


  // --- 3. Location Push Polling (The "Write" Loop) ---
  // Kept exactly as requested. Pushes location every 10s.
  const startLocationUpdates = () => {
    if (locationUpdateInterval) {
      clearInterval(locationUpdateInterval);
    }

    const interval = setInterval(async () => {
      // 1. Simulate movement (every 15s)
      setCurrentLocation(prev => ({
        lat: prev.lat + (Math.random() - 0.5) * 0.001,
        lng: prev.lng + (Math.random() - 0.5) * 0.001,
      }));

      // 2. Push to Backend
      try {
        const { lat, lng } = currentLocation;
        // Fire and forget - we don't need to wait for response
        driverApi.updateLocation(user.id, { latitude: lat, longitude: lng });
        locationApi.updateLocation(user.id, { latitude: lat, longitude: lng });
      } catch {
        // ignore errors to prevent spamming console
      }
    }, 15000); // 15 seconds

    setLocationUpdateInterval(interval);
  };

  // Cleanup interval on unmount
  useEffect(() => {
    return () => {
      if (locationUpdateInterval) clearInterval(locationUpdateInterval);
    };
  }, [locationUpdateInterval]);


  // --- Event Handlers ---

  const handleRegisterRoute = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!destination || !destinationCoords) {
      toast.error('Please select a valid destination');
      return;
    }

    try {
      // 1. Calculate Route Points for Station Matching
      let metroStations: string[] = [];
      if (isLoaded && window.google) {
        const directionsService = new window.google.maps.DirectionsService();
        const result = await directionsService.route({
          origin: currentLocation,
          destination: destinationCoords,
          travelMode: window.google.maps.TravelMode.DRIVING,
        });

        if (result.routes[0]?.overview_path) {
          const points = result.routes[0].overview_path.map(p => ({
            latitude: p.lat(),
            longitude: p.lng()
          }));
          // Fetch stations (One-time REST call)
          const { data } = await stationApi.getStationsAlongRoute(
            `${currentLocation.lat},${currentLocation.lng}`,
            destination,
            points
          );
          metroStations = data?.stations?.map((s: any) => s.name) || [];
        }
      }

      // 2. Register with Backend
      // CRITICAL FIX: Ensure location is updated BEFORE registering route so Matching Service can calculate fare
      await driverApi.updateLocation(user.id, {
        latitude: currentLocation.lat,
        longitude: currentLocation.lng
      });

      await driverApi.registerRoute(user.id, {
        destination,
        availableSeats,
        metroStations,
      });

      // 3. Update Local State
      const newRoute = {
        id: 'route_' + Date.now(),
        driverId: user.id,
        currentLocation,
        destination,
        destinationCoords,
        availableSeats,
      };

      setActiveRoute(newRoute);
      localStorage.setItem(ROUTE_STORAGE_KEY, JSON.stringify(newRoute));
      setShowRouteDialog(false);
      toast.success(`Route registered! Matching with riders near ${metroStations.length} stations.`);

      // 4. Start the Push Polling Loop
      startLocationUpdates();

    } catch (error) {
      console.error(error);
      toast.error('Failed to register route');
    }
  };

  const handleAcceptMatch = async () => {
    if (!incomingMatch) return;
    try {
      await matchingApi.acceptMatch(incomingMatch.matchId, { driverId: user.id });
      toast.success("Ride Accepted!");
      setIncomingMatch(null);
      // The stream will update the active trips list automatically
    } catch (error) {
      console.error("Accept match error", error);
      toast.error("Failed to accept ride");
    }
  };

  const handleDeclineMatch = async () => {
    if (!incomingMatch) return;
    try {
      await matchingApi.declineMatch(incomingMatch.matchId, { driverId: user.id });
      toast.info("Ride Declined");
      setIncomingMatch(null);
    } catch (error) {
      console.error("Decline match error", error);
    }
  };

  const handlePickup = async (tripId: string) => {
    try {
      await tripApi.recordPickup(tripId, {
        latitude: currentLocation.lat,
        longitude: currentLocation.lng
      });
      toast.success('Pickup confirmed!');

      // Optimistic Update: Update trip status immediately
      setTrips(prev => prev.map(t =>
        t.id === tripId ? { ...t, status: 'active' } : t
      ));
    } catch (error) {
      toast.error('Failed to confirm pickup');
    }
  };

  const handleDropoff = async (tripId: string) => {
    const trip = trips.find(t => t.id === tripId);
    try {
      await tripApi.recordDropoff(tripId, {
        latitude: currentLocation.lat,
        longitude: currentLocation.lng
      });

      // Logic moved to Backend (Stream Update)
      // We only handle UI triggers here

      toast.success('Drop-off confirmed!');

      if (trip) {
        setSelectedRiderForRating(trip);
        setRatingDialogOpen(true);
      }
    } catch (error) {
      toast.error('Failed to confirm drop-off');
    }
  };

  const handleRateRider = async (rating: number) => {
    if (!selectedRiderForRating) return;
    try {
      // Call your Rating API here
      toast.success("Rating submitted");
    } catch (e) {
      toast.error("Could not submit rating");
    }
  };

  // --- Render Helpers ---

  if (loadError) return <div>Error loading maps configuration.</div>;

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">

        {/* Header Section */}
        <div className="mb-8 bg-white rounded-lg shadow-sm p-6">
          <div className="flex items-center gap-6">
            <div className="w-20 h-20 rounded-full bg-gradient-to-br from-blue-500 to-purple-500 flex items-center justify-center text-white text-3xl font-semibold">
              {user.name?.charAt(0).toUpperCase() || 'D'}
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
                  <span>₹{totalEarnings} Earned</span>
                </div>
                <div className="flex items-center gap-1">
                  <Users className="h-5 w-5 text-blue-500" />
                  <span>{activeRoute?.availableSeats ?? '0'} Seats Avail.</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Action Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

          {/* LEFT COLUMN: Map & Route */}
          <div className="lg:col-span-2 space-y-6">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle>Live Map</CardTitle>
                  {!activeRoute && (
                    <Dialog open={showRouteDialog} onOpenChange={setShowRouteDialog}>
                      <DialogTrigger asChild>
                        <Button><Plus className="h-4 w-4 mr-2" /> Start Route</Button>
                      </DialogTrigger>
                      <DialogContent className="sm:max-w-[425px]">
                        <DialogHeader>
                          <DialogTitle>Register Route</DialogTitle>
                          <DialogDescription>Where are you heading?</DialogDescription>
                        </DialogHeader>
                        <form onSubmit={handleRegisterRoute} className="space-y-4">
                          <Label>Destination</Label>
                          {isLoaded && (
                            <PlaceSearchBox
                              onPlaceSelect={(place) => {
                                setDestination(place.address);
                                setDestinationCoords(place.coords);
                              }}
                            />
                          )}
                          <Label>Seats</Label>
                          <Input type="number" min="1" max="8" value={availableSeats} onChange={e => setAvailableSeats(Number(e.target.value))} />
                          <Button type="submit" className="w-full">Start Driving</Button>
                        </form>
                      </DialogContent>
                    </Dialog>
                  )}
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
                  <div className="mt-4 p-3 bg-blue-50 text-blue-800 rounded flex justify-between">
                    <span>Heading to: <strong>{activeRoute.destination}</strong></span>
                    <Button variant="ghost" size="sm" onClick={async () => {
                      localStorage.removeItem(ROUTE_STORAGE_KEY);
                      setActiveRoute(null);
                      setTrips([]);
                      if (locationUpdateInterval) clearInterval(locationUpdateInterval);

                      // Clear route on backend so it doesn't persist on reload
                      try {
                        await driverApi.registerRoute(user.id, {
                          destination: '',
                          availableSeats: 0,
                          metroStations: []
                        });
                        toast.success("Route ended");
                      } catch (e) {
                        console.error("Failed to clear route on backend", e);
                      }
                    }}>End Route</Button>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Active Trips List */}
            <Card>
              <CardHeader><CardTitle>Active Trips</CardTitle></CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {trips.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">Waiting for ride requests...</div>
                  ) : (
                    trips.map(trip => (
                      <div key={trip.id} className="border p-4 rounded-lg flex flex-col gap-2">
                        <div className="flex justify-between">
                          <span className="font-semibold">{trip.riderName}</span>
                          <Badge>{trip.status}</Badge>
                        </div>
                        <div className="text-sm text-gray-600 grid grid-cols-2 gap-2">
                          <span>📍 Pickup: {trip.pickupStation}</span>
                          <span>🏁 Drop: {trip.destination}</span>
                          <span>💰 Fare: ₹{trip.fare}</span>
                        </div>
                        <div className="flex gap-2 mt-2">
                          {trip.status === 'scheduled' && (
                            <Button size="sm" className="w-full" onClick={() => handlePickup(trip.id)}>
                              <CheckCircle2 className="w-4 h-4 mr-2" /> Arrived at Pickup
                            </Button>
                          )}
                          {trip.status === 'active' && (
                            <Button size="sm" className="w-full" onClick={() => handleDropoff(trip.id)}>
                              <CheckCircle2 className="w-4 h-4 mr-2" /> Complete Dropoff
                            </Button>
                          )}
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* RIGHT COLUMN: Stats & History */}
          <div className="space-y-6">
            <Card>
              <CardHeader><CardTitle>Ride History</CardTitle></CardHeader>
              <CardContent>
                <Tabs defaultValue="recent">
                  <TabsList className="w-full">
                    <TabsTrigger value="recent" className="flex-1">Recent</TabsTrigger>
                    <TabsTrigger value="all" className="flex-1">All</TabsTrigger>
                  </TabsList>
                  <TabsContent value="recent">
                    {rideHistory.slice(0, 3).map(ride => (
                      <div key={ride.id} className="text-sm border-b py-2 last:border-0">
                        <div className="flex justify-between font-medium">
                          <span>{ride.riderName}</span>
                          <span className="text-green-600">₹{ride.fare}</span>
                        </div>
                        <div className="text-gray-500 text-xs">{ride.date} • {ride.destination}</div>
                      </div>
                    ))}
                  </TabsContent>
                  <TabsContent value="all">
                    {rideHistory.length === 0 ? (
                      <div className="text-center py-4 text-gray-500 text-sm">No past rides found</div>
                    ) : (
                      <div className="max-h-60 overflow-y-auto pr-1">
                        {rideHistory.map(ride => (
                          <div key={ride.id} className="text-sm border-b py-2 last:border-0">
                            <div className="flex justify-between font-medium">
                              <span>{ride.riderName}</span>
                              <span className="text-green-600">₹{ride.fare}</span>
                            </div>
                            <div className="text-gray-500 text-xs">{ride.date} • {ride.destination}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </TabsContent>
                </Tabs>
              </CardContent>
            </Card>
          </div>

        </div>
      </div>

      {/* Incoming Match Dialog */}
      <Dialog open={!!incomingMatch} onOpenChange={(open) => !open && setIncomingMatch(null)}>
        <DialogContent className="sm:max-w-[600px] border-l-4 border-l-blue-600 shadow-2xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-xl text-blue-700">
              <Car className="h-6 w-6" />
              New Ride Request!
            </DialogTitle>
            <DialogDescription className="text-base">
              A rider is looking for a ride near you.
            </DialogDescription>
          </DialogHeader>

          {incomingMatch && (
            <div className="space-y-6 py-4">
              <div className="flex items-center justify-between bg-green-50 p-4 rounded-lg border border-green-100">
                <div>
                  <p className="text-sm text-green-800 font-medium">Estimated Fare</p>
                  <p className="text-3xl font-bold text-green-700">₹{incomingMatch.fare}</p>
                </div>
                <Badge className="bg-green-600 text-white px-3 py-1 text-sm">Cash / UPI</Badge>
              </div>

              <div className="space-y-4">
                <div className="flex items-start gap-4">
                  <div className="mt-1 bg-blue-100 p-2 rounded-full">
                    <MapPin className="h-5 w-5 text-blue-600" />
                  </div>
                  <div>
                    <p className="text-sm text-gray-500 font-medium">Pickup</p>
                    <p className="text-lg font-semibold text-gray-900">{incomingMatch.pickupStation}</p>
                  </div>
                </div>

                <div className="flex items-center justify-center">
                  <div className="h-8 w-0.5 bg-gray-200"></div>
                </div>

                <div className="flex items-start gap-4">
                  <div className="mt-1 bg-red-100 p-2 rounded-full">
                    <MapPin className="h-5 w-5 text-red-600" />
                  </div>
                  <div>
                    <p className="text-sm text-gray-500 font-medium">Destination</p>
                    <p className="text-lg font-semibold text-gray-900">{incomingMatch.destination}</p>
                  </div>
                </div>
              </div>
            </div>
          )}

          <DialogFooter className="gap-3 sm:gap-0">
            <Button
              variant="outline"
              onClick={handleDeclineMatch}
              className="flex-1 h-12 text-base border-red-200 text-red-700 hover:bg-red-50 hover:text-red-800"
            >
              <XCircle className="mr-2 h-5 w-5" />
              Decline
            </Button>
            <Button
              className="flex-1 h-12 text-base !bg-blue-600 hover:!bg-blue-700 font-bold shadow-lg transition-all hover:scale-105 !text-white"
              onClick={handleAcceptMatch}
            >
              <CheckCircle2 className="mr-2 h-5 w-5" />
              Accept Ride
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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