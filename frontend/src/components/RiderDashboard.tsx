import { useState, useEffect } from 'react';
import { Button } from './ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Badge } from './ui/badge';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from './ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';
import { MapPin, Clock, Car, Plus, CheckCircle2, XCircle, Loader2, Navigation, Star, Phone } from 'lucide-react';
import { toast } from 'sonner@2.0.3';
import { riderApi, matchingApi, stationApi, tripApi, driverApi } from '../lib/api';
import { MapView } from './MapView';
import { RatingDialog } from './RatingDialog';
import { LocationSearch } from './LocationSearch';

interface RiderDashboardProps {
  user: any;
}

export function RiderDashboard({ user }: RiderDashboardProps) {
  const [rides, setRides] = useState<any[]>([]);
  const [showRequestDialog, setShowRequestDialog] = useState(false);
  const [activeRide, setActiveRide] = useState<any>(null);
  const [ratingDialogOpen, setRatingDialogOpen] = useState(false);
  const [selectedDriverForRating, setSelectedDriverForRating] = useState<any>(null);
  const [rideAccepted, setRideAccepted] = useState(false);
  const [inRide, setInRide] = useState(false);

  // Ride request form
  const [metroStation, setMetroStation] = useState('');
  const [destination, setDestination] = useState('');
  const [destinationCoords, setDestinationCoords] = useState<any>(null);
  const [arrivalTime, setArrivalTime] = useState('');
  const [matching, setMatching] = useState(false);
  const [stations, setStations] = useState<any[]>([]);

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

    // Fetch ride requests for this rider from backend
    riderApi.listRideRequests(user.id)
      .then(({ data }) => {
        const arr = Array.isArray(data?.ride_requests) ? data.ride_requests : (Array.isArray(data?.rideRequests) ? data.rideRequests : []);
        const items = arr.map((rr: any) => ({
              id: rr.ride_request_id || rr.rideRequestId,
              riderId: rr.rider_id || rr.riderId,
              metroStation: rr.metro_station || rr.metroStation,
              destination: rr.destination,
              destinationCoords: undefined,
              arrivalTime: rr.arrival_time || rr.arrivalTime,
              status: (rr.status === 0 || rr.status === 'PENDING') ? 'pending' :
                      (rr.status === 1 || rr.status === 'MATCHED') ? 'matched' :
                      (rr.status === 2 || rr.status === 'IN_PROGRESS') ? 'active' :
                      (rr.status === 3 || rr.status === 'COMPLETED') ? 'completed' :
                      (rr.status === 4 || rr.status === 'CANCELLED') ? 'cancelled' : 'pending',
              fare: Math.floor(Math.random() * 100) + 100,
              tripId: rr.trip_id || rr.tripId,
            }))
          ;
        setRides(items);
        if (items.length) {
          const current = items.find(x => x.status === 'matched') || items.find(x => x.status === 'pending');
          if (current) setActiveRide(current);
        }
      })
      .catch(() => {});
  }, [user.id]);

  const handleRequestRide = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!destination || !destinationCoords) {
      toast.error('Please select a destination from the search');
      return;
    }

    setMatching(true);

    try {
      // 1) Compute arrival time (fallback: now). Use epoch seconds, ensure valid.
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
      const reg = await riderApi.registerRideRequest({
        rider_id: user.id,
        metro_station: metroStation,
        destination,
        arrival_time: arrivalEpoch,
      });
      const respData = reg?.data || {};
      console.debug('Ride request raw response', respData);
      const ride_request_id = respData.ride_request_id || respData.rideRequestId;
      if (!ride_request_id) {
        toast.error(`Debug: ride request response keys => ${Object.keys(respData).join(', ') || 'NONE'}`);
        throw new Error('No ride_request_id');
      }

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
      setRides(prev => ([...prev, pendingRide]));
      // Surface the newly created request in the Current Ride panel
      setActiveRide(pendingRide);
      setShowRequestDialog(false);
      toast.success('Ride request submitted! Matching with nearby drivers...');

      // 2) Request matching
      const matchResp = await matchingApi.matchRiderWithDriver({
        ride_request_id,
        rider_id: user.id,
        metro_station: metroStation,
        destination,
        arrival_time: arrivalEpoch,
      });
      const matchId = matchResp?.data?.match_id || ride_request_id;

      // 3) Poll match status until MATCHED
      let attempts = 0;
      const poll = setInterval(async () => {
        attempts++;
        try {
          const { data } = await matchingApi.getMatchStatus(matchId);
          const statusVal = data?.status ?? data?.rideStatus;
          if (data?.success && (statusVal === 'MATCHED' || statusVal === 1)) {
            clearInterval(poll);
            const driverId = data.driver_id || data.driverId;
            const tripId = data.trip_id || data.tripId;
            let driverInfo: any = null;
            try { driverInfo = (await driverApi.getDriverInfo(driverId)).data; } catch {}

            const matchedRide = {
              ...pendingRide,
              status: 'matched',
              tripId,
              driver: {
                id: driverId,
                name: `Driver ${driverId.substring(0, 5)}`,
                vehicle: '—',
                rating: 4.8,
                phone: '',
                currentLocation: driverInfo?.current_location ? {
                  latitude: driverInfo.current_location.latitude,
                  longitude: driverInfo.current_location.longitude,
                } : undefined,
              },
            };
            setRides(prev => prev.map(r => r.id === pendingRide.id ? matchedRide : r));
            setActiveRide(matchedRide);
            toast.success('Match found! Your driver is on the way.');
          }
        } catch {}
        if (attempts > 15) { clearInterval(poll); toast.error('Unable to find a match right now.'); }
      }, 2000);

    } catch (error: any) {
      const serverMsg = error?.response?.data?.message || error?.message;
      // Provide extra debug when missing ID
      if (serverMsg?.includes('No ride_request_id')) {
        console.error('Ride request failure details', error?.response?.data || error);
      }
      toast.error(`Failed to request ride${serverMsg ? `: ${serverMsg}` : ''}`);
    } finally {
      setMatching(false);
    }
  };

  const handleAcceptRide = () => {
    setRideAccepted(true);
    toast.success('Ride accepted! You can track your driver on the map.');
  };

  const handleEnterRide = async () => {
    setInRide(true);
    if (activeRide) {
      try {
        if (activeRide.tripId) {
          await tripApi.recordPickup(activeRide.tripId, { latitude: 0, longitude: 0 });
        }
      } catch {}
      setActiveRide(prev => ({ ...prev, status: 'active' }));
      toast.success('Ride started! Navigating to your destination.');
    }
  };

  const handleCompleteRide = async () => {
    if (activeRide?.driver) {
      try {
        if (activeRide.tripId) {
          await tripApi.recordDropoff(activeRide.tripId, { latitude: 0, longitude: 0 });
        }
      } catch {}
      setSelectedDriverForRating(activeRide.driver);
      setRatingDialogOpen(true);
      setActiveRide(prev => ({ ...prev, status: 'completed' }));
      setRideAccepted(false);
      setInRide(false);
      toast.success('Ride completed! Please rate your driver.');
    }
  };

  const handleRateDriver = (rating: number, feedback: string) => {
    toast.success(`Thank you for rating ${selectedDriverForRating?.name}!`);
    // In production, send rating to backend
  };

  const handleCancelRide = async (rideId: string) => {
    try {
      await riderApi.deleteRideRequest(rideId);
      setRides(prev => prev.filter((ride: any) => ride.id !== rideId));
      if (activeRide?.id === rideId) {
        setActiveRide(null);
        setRideAccepted(false);
        setInRide(false);
      }
      toast.success('Ride cancelled');
    } catch (error) {
      toast.error('Failed to cancel ride');
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
                <LocationSearch
                  value={destination}
                  onChange={(location, coords) => { setDestination(location); setDestinationCoords(coords); }}
                  label="Destination"
                  placeholder="Search destination..."
                />
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
                <Button type="submit" disabled={matching || !metroStation || !destination} className="w-full mt-2">
                  {matching ? (
                    <><Loader2 className="h-4 w-4 mr-2 animate-spin" />Matching...</>
                  ) : 'Request Ride'}
                </Button>
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
                      {!rideAccepted && activeRide.status === 'matched' && (
                        <Button
                          className="w-full"
                          onClick={handleAcceptRide}
                        >
                          <CheckCircle2 className="h-4 w-4 mr-2" />
                          Accept Ride
                        </Button>
                      )}

                      {rideAccepted && !inRide && activeRide.status !== 'active' && (
                        <Button
                          className="w-full"
                          onClick={handleEnterRide}
                        >
                          <Car className="h-4 w-4 mr-2" />
                          I'm in the Car
                        </Button>
                      )}

                      {inRide && activeRide.status === 'active' && (
                        <Button
                          className="w-full"
                          onClick={handleCompleteRide}
                        >
                          <CheckCircle2 className="h-4 w-4 mr-2" />
                          Complete Ride
                        </Button>
                      )}

                      {activeRide.status !== 'completed' && activeRide.status !== 'cancelled' && (
                        <Button
                          variant="destructive"
                          className="w-full"
                          onClick={() => handleCancelRide(activeRide.id)}
                        >
                          <XCircle className="h-4 w-4 mr-2" />
                          Cancel Ride
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
                          <Button variant="destructive" size="sm" onClick={() => handleCancelRide(ride.id)}>Cancel</Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Map View */}
            {activeRide && rideAccepted && (
              <Card>
                <CardHeader>
                  <CardTitle>Live Tracking</CardTitle>
                  <CardDescription>
                    {inRide ? 'Navigating to your destination' : 'Driver is on the way to pick you up'}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <MapView
                    driverLocation={inRide ? undefined : activeRide.driver?.currentLocation}
                    destination={inRide ? activeRide.destinationCoords : undefined}
                    showRoute={inRide}
                  />
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
                  Contact Support
                </Button>
                <Button variant="outline" className="w-full justify-start bg-white">
                  <MapPin className="h-4 w-4 mr-2" />
                  Report Issue
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      {/* Rating Dialog */}
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
