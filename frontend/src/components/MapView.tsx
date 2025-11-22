import React, { useCallback, useEffect, useRef, useState } from "react";
import { Card } from "./ui/card";
import {
  GoogleMap,
  Marker,
  DirectionsRenderer,
} from "@react-google-maps/api";

const containerStyle = { width: "100%", height: "100%" };

type LatLng = { lat: number; lng: number };

// Helper to check if coordinates are valid numbers
const isValidLatLng = (coords: any): coords is LatLng => {
  return (
    coords &&
    typeof coords.lat === "number" &&
    typeof coords.lng === "number" &&
    !isNaN(coords.lat) &&
    !isNaN(coords.lng)
  );
};

// ----------------------------------------------------------------------
// Helper Component: Uses the Modern Google Places Web Component
// ----------------------------------------------------------------------
export const PlaceSearchBox = ({
  placeholder,
  onPlaceSelect,
}: {
  placeholder: string;
  onPlaceSelect: (place: { coords: LatLng; address: string }) => void;
}) => {
  const inputContainerRef = useRef<HTMLDivElement>(null);
  // Keep track if we've already appended the element to prevent duplicates
  const autocompleteRef = useRef<any>(null);

  useEffect(() => {
    if (!inputContainerRef.current || !window.google) return;

    let active = true;

    const init = async () => {
      // Import the NEW Places Library
      // @ts-ignore: Types might be missing for beta API
      const { PlaceAutocompleteElement } = (await google.maps.importLibrary(
        "places"
      )) as any;

      if (!active) return;

      // Only create the element once
      if (!autocompleteRef.current) {
        const autocomplete = new PlaceAutocompleteElement();
        autocomplete.placeholder = placeholder;
        autocompleteRef.current = autocomplete;

        // --- STYLING FIX ---
        // Apply classes for width/border
        autocomplete.classList.add(
          "w-full",
          "h-10",
          "rounded-md",
          "border",
          "border-input",
          "bg-white", // Force white background on the input itself
          "text-black" // Force black text on the input itself
        );
        
        // Apply inline styles to the Custom Element to force CSS Variables
        // This fixes the black dropdown issue by overriding Shadow DOM variables
        Object.assign(autocomplete.style, {
            width: '100%',
            height: '40px',
            backgroundColor: '#ffffff',
            color: '#000000',
            // Google Maps Web Component Variables
            '--gmp-px-color-surface': '#ffffff', // Dropdown background
            '--gmp-px-color-text-primary': '#000000', // Main text
            '--gmp-px-color-text-secondary': '#4b5563', // Secondary text
            '--gmp-px-color-text-suggestion-primary': '#000000',
            '--gmp-px-color-text-suggestion-secondary': '#4b5563',
            '--gmp-px-color-separator': '#e5e7eb',
        });

        // --- SELECTION LOGIC FIX ---
        // --- SELECTION LOGIC FIX ---
      autocomplete.addEventListener("gmp-select", async ({ placePrediction }) => {
        const place = placePrediction.toPlace();
        if (!place) {
          console.warn("No place selected or event data is missing.");
          return;
        }

        try {
          // Fetch fields explicitly in the new API
          await place.fetchFields({
            fields: ["displayName", "formattedAddress", "location"],
          });

          const location = place.location;
          let name = place.displayName;
          if (typeof name === "object" && name !== null && "text" in name) {
            name = name.text;
          }

          const address = place.formattedAddress || name || "Selected Location";

          if (location) {
            // Trigger parent update immediately
            onPlaceSelect({
              coords: { lat: location.lat(), lng: location.lng() },
              address: address,
            });
          }
        } catch (err) {
          console.error("Error fetching place details:", err);
        }
      });

        // Append to the DOM
        inputContainerRef.current.appendChild(autocomplete);
      }
    };

    init();

    return () => {
      active = false;
    };
  }, [placeholder]); // Removed onPlaceSelect from deps to avoid re-init

  return <div ref={inputContainerRef} className="w-full text-black" />;
};

// ----------------------------------------------------------------------
// Main Component
// ----------------------------------------------------------------------
interface MapViewProps {
  isLoaded: boolean; // Received from parent who loads the script
  currentLocation?: LatLng | null; // Optional override
  destination?: { lat: number; lng: number; name: string };
  showRoute?: boolean;
}

export function MapView({
  isLoaded,
  currentLocation: propCurrentLocation,
  destination,
  showRoute,
}: MapViewProps) {
  const [mapCenter, setMapCenter] = useState<LatLng>({
    lat: 28.6139,
    lng: 77.209,
  });
  const [internalCurrentLocation, setInternalCurrentLocation] =
    useState<LatLng | null>(null);

  // Use prop location if provided and valid, otherwise use internal state
  const currentLocation = isValidLatLng(propCurrentLocation)
    ? propCurrentLocation
    : internalCurrentLocation;

  const [originPlace, setOriginPlace] = useState<{
    coords?: LatLng;
    address?: string;
  } | null>(null);
  const [destPlace, setDestPlace] = useState<{
    coords?: LatLng;
    address?: string;
  } | null>(null);
  const [directions, setDirections] =
    useState<google.maps.DirectionsResult | null>(null);
  const [loadingRoute, setLoadingRoute] = useState(false);

  const mapRef = useRef<google.maps.Map | null>(null);

  // Handle Internal Geolocation if no prop provided
  useEffect(() => {
    if (isValidLatLng(propCurrentLocation)) {
      setMapCenter(propCurrentLocation);
    } else if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const coords = { lat: pos.coords.latitude, lng: pos.coords.longitude };
          setInternalCurrentLocation(coords);
          // Only update center if we haven't received a valid prop location yet
          if (!isValidLatLng(propCurrentLocation)) {
            setMapCenter(coords);
          }
        },
        (err) => {
          console.warn("Geolocation error:", err);
        },
        { enableHighAccuracy: true }
      );
    }
  }, [propCurrentLocation]);

  // Effect to handle route calculation if destination prop changes (from parent)
  useEffect(() => {
    if (
      isLoaded &&
      showRoute &&
      destination &&
      isValidLatLng(destination) &&
      isValidLatLng(currentLocation)
    ) {
      const ds = new google.maps.DirectionsService();
      ds.route(
        {
          origin: currentLocation,
          destination: { lat: destination.lat, lng: destination.lng },
          travelMode: google.maps.TravelMode.DRIVING,
        },
        (result, status) => {
          if (status === google.maps.DirectionsStatus.OK && result) {
            setDirections(result);
            if (mapRef.current) {
              mapRef.current.fitBounds(result.routes[0].bounds);
            }
          }
        }
      );
    }
  }, [isLoaded, showRoute, destination, currentLocation]);

  const onLoadMap = useCallback((map: google.maps.Map) => {
    mapRef.current = map;
  }, []);

  const requestRoute = async () => {
    const origin = originPlace?.coords ?? currentLocation;
    const destination = destPlace?.coords;

    if (!origin || !destination) {
      alert("Please provide both origin and destination.");
      return;
    }
    setLoadingRoute(true);

    const ds = new google.maps.DirectionsService();
    ds.route(
      {
        origin,
        destination,
        travelMode: google.maps.TravelMode.DRIVING,
        drivingOptions: { departureTime: new Date() },
      },
      (result, status) => {
        setLoadingRoute(false);
        if (status === google.maps.DirectionsStatus.OK && result) {
          setDirections(result);
          const routeBounds = result.routes[0].bounds;
          if (mapRef.current) mapRef.current.fitBounds(routeBounds);
        } else {
          console.error("Directions request failed:", status);
          alert("Could not get route. Try again.");
        }
      }
    );
  };

  if (!isLoaded) {
    return (
      <Card className="h-full min-h-[400px] p-4 flex items-center justify-center">
        <div>Loading map...</div>
      </Card>
    );
  }

  return (
    <Card className="h-full min-h-[400px] relative overflow-hidden">
      {/* Only show manual controls if NOT in active route mode controlled by parent */}
      {!showRoute && (
        <div className="absolute top-4 left-4 right-4 z-30 pointer-events-auto">
          <div className="flex gap-2 items-center bg-white/90 p-2 rounded-lg backdrop-blur-sm shadow-md">
            <div className="flex-1 max-w-[420px]">
              <PlaceSearchBox
                placeholder={
                  currentLocation ? "Current Location (or search)" : "Search Source"
                }
                onPlaceSelect={(place) => {
                  setOriginPlace(place);
                  if (isValidLatLng(place.coords)) setMapCenter(place.coords);
                }}
              />
            </div>

            <div className="flex-1 max-w-[420px]">
              <PlaceSearchBox
                placeholder="Search Destination"
                onPlaceSelect={(place) => {
                  setDestPlace(place);
                  if (isValidLatLng(place.coords)) setMapCenter(place.coords);
                }}
              />
            </div>

            <button
              onClick={requestRoute}
              disabled={loadingRoute}
              className="px-4 py-2 rounded-md bg-sky-600 text-white hover:bg-sky-700 whitespace-nowrap text-sm font-medium"
            >
              {loadingRoute ? "Routing..." : "Get Route"}
            </button>
          </div>
        </div>
      )}

      {/* Map */}
      <div className="absolute inset-0">
        <GoogleMap
          mapContainerStyle={containerStyle}
          // Fallback center to prevent crashes if mapCenter becomes invalid
          center={isValidLatLng(mapCenter) ? mapCenter : { lat: 28.6139, lng: 77.209 }}
          zoom={14}
          onLoad={onLoadMap}
          options={{
            streetViewControl: false,
            mapTypeControl: false,
            fullscreenControl: false,
            zoomControl: true,
          }}
        >
          {isValidLatLng(currentLocation) && (
            <Marker
              position={currentLocation}
              icon={{
                path: google.maps.SymbolPath.CIRCLE,
                scale: 7,
                fillColor: "#1976D2",
                fillOpacity: 1,
                strokeWeight: 2,
                strokeColor: "#fff",
              }}
              title="Your location"
            />
          )}

          {/* origin/destination markers for manual search */}
          {isValidLatLng(originPlace?.coords) && (
            <Marker position={originPlace!.coords!} title={originPlace!.address} />
          )}
          {isValidLatLng(destPlace?.coords) && (
            <Marker position={destPlace!.coords!} title={destPlace!.address} />
          )}

          {/* render route */}
          {directions && (
            <DirectionsRenderer
              directions={directions}
              options={{
                preserveViewport: false,
                polylineOptions: {
                  strokeColor: "#1976D2",
                  strokeWeight: 6,
                },
                markerOptions: {
                  visible: false,
                },
              }}
            />
          )}
        </GoogleMap>
      </div>
    </Card>
  );
}

export default MapView;