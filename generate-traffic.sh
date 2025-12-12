#!/bin/bash

BASE_URL="http://localhost:8080"
CONTENT_TYPE="Content-Type: application/json"

# Generate unique suffix based on timestamp
TS=$(date +%s)
RIDER_EMAIL="rider_${TS}@test.com"
DRIVER_EMAIL="driver_${TS}@test.com"
RIDER_PHONE="${TS: -10}"
DRIVER_PHONE="$((TS + 1))"
DRIVER_PHONE="${DRIVER_PHONE: -10}"

# IDs for service calls (using timestamp to ensure uniqueness in logs)
DRIVER_ID="driver-${TS}"
RIDER_ID="rider-${TS}"

echo "--------------------------------------------------"
echo "Generating Traffic with ID: $TS"
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
echo "[5/5] Rider Service: $RIDER_ID requesting a ride..."
curl -s -X POST "$BASE_URL/rider/ride-register/$RIDER_ID" \
  -H "$CONTENT_TYPE" \
  -H "$AUTH_HEADER" \
  -d "{\"rider_id\": \"$RIDER_ID\", \"metro_station\": \"Central Station\", \"destination\": \"Tech Park\", \"arrival_time\": 1702290000}"
echo ""

echo "--------------------------------------------------"
echo "Traffic generated! Check Kibana for:"
echo " - Rider: $RIDER_EMAIL ($RIDER_ID)"
echo " - Driver: $DRIVER_EMAIL ($DRIVER_ID)"
echo "--------------------------------------------------"
