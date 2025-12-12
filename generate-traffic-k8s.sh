#!/bin/bash

# Configuration
# Using Port Forward# Using Port Forwarding (Reliable)
BASE_URL="http://localhost:8080"
CONTENT_TYPE="Content-Type: application/json"

# Check if Gateway is reachable
echo "Checking connectivity to $BASE_URL..."
curl -s --head --request GET $BASE_URL | grep "200 OK" > /dev/null
if [ $? -ne 0 ]; then
    echo "Warning: Cannot connect to $BASE_URL. Ensure 'kubectl port-forward svc/lastmile-gateway 8080:8080' is running."
fi

# Generate unique suffix based on timestamp
TS=$(date +%s)
RIDER_EMAIL="rider_${TS}@test.com"
DRIVER_EMAIL="driver_${TS}@test.com"
RIDER_PHONE="${TS: -10}"
DRIVER_PHONE="$((TS + 1))"
DRIVER_PHONE="${DRIVER_PHONE: -10}"

# IDs for service calls
DRIVER_ID="driver-${TS}"
RIDER_ID="rider-${TS}"

echo "--------------------------------------------------"
echo "Generating K8s Traffic with ID: $TS"
echo "--------------------------------------------------"

# 1. Register Rider
echo "[1/5] Registering Rider: $RIDER_EMAIL"
RIDER_RESP=$(curl -s -X POST "$BASE_URL/user/register" \
  -H "$CONTENT_TYPE" \
  -d "{\"email\": \"$RIDER_EMAIL\", \"password\": \"password123\", \"name\": \"Rider $TS\", \"phone\": \"$RIDER_PHONE\", \"user_type\": \"RIDER\"}")
echo "Response: $RIDER_RESP"

# Extract token
TOKEN=$(echo $RIDER_RESP | grep -o '"token":"[^"]*' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then TOKEN="ha-test-token"; fi
AUTH_HEADER="Authorization: Bearer $TOKEN"

echo ""

# 2. Register Driver
echo "[2/5] Registering Driver: $DRIVER_EMAIL"
curl -s -X POST "$BASE_URL/user/register" \
  -H "$CONTENT_TYPE" \
  -d "{\"email\": \"$DRIVER_EMAIL\", \"password\": \"password123\", \"name\": \"Driver $TS\", \"phone\": \"$DRIVER_PHONE\", \"user_type\": \"DRIVER\"}"
echo ""

# 3. Driver Registers Route
echo "[3/5] Driver Service: Registering route for $DRIVER_ID..."
curl -s -X POST "$BASE_URL/driver/$DRIVER_ID/register-route" \
  -H "$CONTENT_TYPE" \
  -H "$AUTH_HEADER" \
  -d "{\"driver_id\": \"$DRIVER_ID\", \"destination\": \"Tech Park\", \"available_seats\": 3, \"metro_stations\": [\"Central Station\", \"North Station\"]}"
echo ""

# 4. Driver Updates Location
echo "[4/5] Location Service: Updating location for $DRIVER_ID..."
curl -s -X POST "$BASE_URL/location/$DRIVER_ID" \
  -H "$CONTENT_TYPE" \
  -H "$AUTH_HEADER" \
  -d "{\"driver_id\": \"$DRIVER_ID\", \"latitude\": 40.7128, \"longitude\": -74.0060}"
echo ""

# 5. Rider Requests Ride
echo "[5/6] Rider Service: $RIDER_ID requesting a ride..."
RIDE_RESP=$(curl -s -X POST "$BASE_URL/rider/ride-register/$RIDER_ID" \
  -H "$CONTENT_TYPE" \
  -H "$AUTH_HEADER" \
  -d "{\"rider_id\": \"$RIDER_ID\", \"metro_station\": \"Central Station\", \"destination\": \"Tech Park\", \"arrival_time\": 1702290000}")
echo "Response: $RIDE_RESP"
RIDE_REQ_ID=$(echo $RIDE_RESP | grep -o '"rideRequestId":"[^"]*' | cut -d'"' -f4)

echo ""

# 6. Trigger Matching
echo "[6/6] Matching Service: Triggering match..."
curl -s -X POST "$BASE_URL/match" \
  -H "$CONTENT_TYPE" \
  -H "$AUTH_HEADER" \
  -d "{\"rider_id\": \"$RIDER_ID\", \"ride_request_id\": \"$RIDE_REQ_ID\", \"metro_station\": \"Central Station\", \"destination\": \"Tech Park\", \"arrival_time\": 1702290000}"
echo ""

echo "--------------------------------------------------"
echo "Traffic generated! Check Kibana for:"
echo " - Rider: $RIDER_EMAIL ($RIDER_ID)"
echo " - Driver: $DRIVER_EMAIL ($DRIVER_ID)"
echo "--------------------------------------------------"
