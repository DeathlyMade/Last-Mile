#!/bin/bash

set -e

docker build -f Dockerfile.station -t lastmile/station-service:latest .
# docker build -f Dockerfile.user -t lastmile/user-service:latest .
# docker build -f Dockerfile.driver -t lastmile/driver-service:latest .
# docker build -f Dockerfile.rider -t lastmile/rider-service:latest .
# docker build -f Dockerfile.location -t lastmile/location-service:latest .
# docker build -f Dockerfile.matching -t lastmile/matching-service:latest .
# docker build -f Dockerfile.trip -t lastmile/trip-service:latest .
# docker build -f Dockerfile.notification -t lastmile/notification-service:latest .

