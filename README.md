# LastMile - Metro Station Drop Service

Project 1
Design and implement a microservice application called LastMile which can be used by
commuters to hire a drop service from metro stations to nearby locations. The application
consists of riders and drivers. Drivers indicate their route and the metro stations near the route
with a number of free seats for riders. Riders indicate the time at which they reach the target
metro station and their destination. Drivers continously update the their location and pickup from
a metro station. You can mimic this by sending periodic updates of the location. The system
matches a one or more riders just minutes before the driver reaches the metro station. For each
metro station, you can keep a list of “nearby” locations and when a driver reaches that location,
you can trigger the match. The target destination must be same as the drivers target destination
for a match. Provide the API reference using Google RPC for implementing this as
microservices. You have to deploy this application on Kubernetes and demonstrate running it
matching riders with drivers. You must demonstrate how the application continues to work in the
presence of failed services and scales from 1 matching service to a maximum of 5 matching
services. The following are a list of microservices that constitute the application
 User Service
•⁠  ⁠Manages rider and driver profiles.
•⁠  ⁠Authentication and authorization.
 Driver Service
•⁠  ⁠Allows drivers to register routes, metro stations, and available seats.
•⁠  ⁠Updates driver location and pickup status.
 Rider Service
•⁠  ⁠Allows riders to register their metro arrival time and destination.
•⁠  ⁠Tracks ride status.
 Matching Service
•⁠  ⁠Matches riders with drivers based on location, time, and destination.
•⁠  ⁠Sends notifications to both parties.
 Trip Service
•⁠  ⁠Manages trip lifecycle: scheduled, active, completed.
•⁠  ⁠Tracks pickup and drop-off events.
 Notification Service
•⁠  ⁠Sends real-time updates to riders and drivers.
•⁠  ⁠Push notifications
 Location Service
•⁠  ⁠Handles real-time location updates from drivers.
•⁠  ⁠Proximity detection.
 Station Service
•⁠  ⁠Maintains metadata about metro stations.
•⁠  ⁠Maps stations to nearby areas

A microservices-based application for connecting commuters with drivers for drop services from metro stations to nearby locations.

## Architecture

### Backend Services (Spring Boot + gRPC)
All services communicate using **gRPC (Google RPC)** for inter-service communication:
- **Station Service** (gRPC Port 50051) - Manages metro station data and returns stations along routes
- **User Service** (gRPC Port 50052) - User authentication and profile management (MongoDB + Redis)
- **Driver Service** (gRPC Port 50053) - Driver route registration and management (MongoDB)
- **Rider Service** (gRPC Port 50054) - Ride request management (MongoDB)
- **Location Service** (gRPC Port 50055) - Real-time location tracking (Redis)
- **Matching Service** (gRPC Port 50056) - Matches riders with drivers
- **Trip Service** (gRPC Port 50057) - Trip lifecycle management (MongoDB)
- **Notification Service** (gRPC Port 50058) - Sends notifications to users

### Frontend (React)
- React application with Material-UI components
- Driver Dashboard for route registration
- Rider Dashboard for ride requests

### Infrastructure
- **MongoDB** - Persistent data storage
- **Redis** - Session management and location caching
- **Kubernetes** - Container orchestration

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Protocol Buffers compiler (protoc) - included via Maven plugin
- Node.js 16+ and npm
- Docker and Docker Compose (for local development)
- Kubernetes cluster (for deployment)
- MongoDB 7.0+
- Redis 7+
- gRPC tools (for testing) - optional

## check for jenkins
## Local Development Setup

### 1. Start Infrastructure Services

#### Using Docker Compose (Recommended)

Create a `docker-compose.yml` file:

```yaml
version: '3.8'
services:
  mongodb:
    image: mongo:7.0
    ports:
      - "27017:27017"
    environment:
      - MONGO_INITDB_DATABASE=lastmile
    volumes:
      - mongodb_data:/data/db

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  mongodb_data:
```

Start services:
```bash
docker-compose up -d
```

#### Manual Setup

**MongoDB:**
```bash
docker run -d -p 27017:27017 --name mongodb mongo:7.0
```

**Redis:**
```bash
docker run -d -p 6379:6379 --name redis redis:7-alpine
```

### 2. Build Backend Services

The services use gRPC with Protocol Buffers. Proto files are in the `proto/` directory and are automatically compiled during Maven build.

Navigate to the backend directory:
```bash
cd backend
```

Build all services (this will compile proto files and generate gRPC stubs):
```bash
./build-all.sh
```

Or build individually:
```bash
cd station-service
mvn clean package -DskipTests
cd ../user-service
mvn clean package -DskipTests
# Repeat for other services...
```

**Note:** The first build will download protoc compiler and generate Java classes from proto files.

### 3. Run Backend Services

Run each service individually:

**Station Service:**
```bash
cd backend/station-service
mvn spring-boot:run
```

**User Service:**
```bash
cd backend/user-service
mvn spring-boot:run
```

**Driver Service:**
```bash
cd backend/driver-service
mvn spring-boot:run
```

**Rider Service:**
```bash
cd backend/rider-service
mvn spring-boot:run
```

**Location Service:**
```bash
cd backend/location-service
mvn spring-boot:run
```

**Matching Service:**
```bash
cd backend/matching-service
mvn spring-boot:run
```

**Trip Service:**
```bash
cd backend/trip-service
mvn spring-boot:run
```

**Notification Service:**
```bash
cd backend/notification-service
mvn spring-boot:run
```

### 4. Run Frontend

Navigate to frontend directory:
```bash
cd frontend
```

Install dependencies:
```bash
npm install
```

Start the development server:
```bash
npm start
```

The frontend will be available at `http://localhost:3000`

## Docker Deployment

### Build Docker Images

From the `backend` directory:
```bash
./build-all.sh
```

Or build individually:
```bash
docker build -f Dockerfile.station -t lastmile/station-service:latest .
docker build -f Dockerfile.user -t lastmile/user-service:latest .
# Repeat for other services...
```

### Run with Docker Compose

Create a `docker-compose.yml` in the root:

```yaml
version: '3.8'
services:
  mongodb:
    image: mongo:7.0
    ports:
      - "27017:27017"
    environment:
      - MONGO_INITDB_DATABASE=lastmile

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  station-service:
    image: lastmile/station-service:latest
    ports:
      - "50051:50051"
    environment:
      - MONGODB_HOST=mongodb
      - MONGODB_PORT=27017
    depends_on:
      - mongodb

  user-service:
    image: lastmile/user-service:latest
    ports:
      - "50052:50052"
    environment:
      - MONGODB_HOST=mongodb
      - MONGODB_PORT=27017
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    depends_on:
      - mongodb
      - redis

  driver-service:
    image: lastmile/driver-service:latest
    ports:
      - "50053:50053"
    environment:
      - MONGODB_HOST=mongodb
      - MONGODB_PORT=27017
      - STATION_SERVICE_HOST=station-service
      - STATION_SERVICE_PORT=50051
    depends_on:
      - mongodb
      - station-service

  rider-service:
    image: lastmile/rider-service:latest
    ports:
      - "50054:50054"
    environment:
      - MONGODB_HOST=mongodb
      - MONGODB_PORT=27017
    depends_on:
      - mongodb

  location-service:
    image: lastmile/location-service:latest
    ports:
      - "50055:50055"
    environment:
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    depends_on:
      - redis

  matching-service:
    image: lastmile/matching-service:latest
    ports:
      - "50056:50056"
    environment:
      - DRIVER_SERVICE_HOST=driver-service
      - DRIVER_SERVICE_PORT=50053
      - TRIP_SERVICE_HOST=trip-service
      - TRIP_SERVICE_PORT=50057
    depends_on:
      - driver-service
      - rider-service
      - trip-service

  trip-service:
    image: lastmile/trip-service:latest
    ports:
      - "50057:50057"
    environment:
      - MONGODB_HOST=mongodb
      - MONGODB_PORT=27017
    depends_on:
      - mongodb

  notification-service:
    image: lastmile/notification-service:latest
    ports:
      - "50058:50058"
```

Run:
```bash
docker-compose up -d
```

## Kubernetes Deployment

### Prerequisites
- Kubernetes cluster running
- kubectl configured

### Deploy Infrastructure

```bash
cd k8s-springboot
kubectl apply -f mongodb.yaml
kubectl apply -f redis.yaml
```

### Deploy Services

Deploy all services:
```bash
./deploy-all.sh
```

Or deploy individually:
```bash
kubectl apply -f station-service.yaml
kubectl apply -f user-service.yaml
kubectl apply -f driver-service.yaml
kubectl apply -f rider-service.yaml
kubectl apply -f location-service.yaml
kubectl apply -f trip-service.yaml
kubectl apply -f notification-service.yaml
kubectl apply -f matching-service.yaml
```

### Scale Matching Service

To scale matching service to 5 replicas:
```bash
kubectl apply -f matching-service-scaled.yaml
```

### Check Deployment Status

```bash
kubectl get pods
kubectl get services
```

### Access Services

Port forward to access services locally:
```bash
kubectl port-forward svc/station-service 50051:50051
kubectl port-forward svc/user-service 50052:50052
kubectl port-forward svc/driver-service 50053:50053
kubectl port-forward svc/rider-service 50054:50054
kubectl port-forward svc/location-service 50055:50055
kubectl port-forward svc/matching-service 50056:50056
kubectl port-forward svc/trip-service 50057:50057
kubectl port-forward svc/notification-service 50058:50058
```

## Testing

### Testing gRPC Services

Since services use gRPC, you'll need gRPC tools for testing. Install grpcurl:

**macOS:**
```bash
brew install grpcurl
```

**Linux:**
```bash
go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest
```

**Windows:**
Download from: https://github.com/fullstorydev/grpcurl/releases

### Test Station Service

**List available services:**
```bash
grpcurl -plaintext localhost:50051 list
```

**Get stations along route:**
```bash
grpcurl -plaintext -d '{
  "origin": "Station A",
  "destination": "North"
}' localhost:50051 com.lastmile.station.StationService/GetStationsAlongRoute
```

**Get all stations:**
```bash
grpcurl -plaintext localhost:50051 com.lastmile.station.StationService/GetAllStations
```

### Test Driver Service

**Register a route:**
```bash
grpcurl -plaintext -d '{
  "driverId": "driver123",
  "originStation": "R1",
  "destination": "North",
  "availableSeats": 4,
  "metroStations": []
}' localhost:50053 com.lastmile.driver.DriverService/RegisterRoute
```

**Get driver info:**
```bash
grpcurl -plaintext -d '{"driverId": "driver123"}' \
  localhost:50053 com.lastmile.driver.DriverService/GetDriverInfo
```

### Test Rider Service

**Register ride request:**
```bash
grpcurl -plaintext -d '{
  "riderId": "rider123",
  "metroStation": "R1",
  "destination": "North",
  "arrivalTime": 1704067200000
}' localhost:50054 com.lastmile.rider.RiderService/RegisterRideRequest
```

### Test Matching Service

**Match rider with driver:**
```bash
grpcurl -plaintext -d '{
  "rideRequestId": "ride-request-id",
  "riderId": "rider123",
  "metroStation": "R1",
  "destination": "North",
  "arrivalTime": 1704067200000
}' localhost:50056 com.lastmile.matching.MatchingService/MatchRiderWithDriver
```

### Test User Service

**Register user:**
```bash
grpcurl -plaintext -d '{
  "email": "test@example.com",
  "password": "password123",
  "name": "Test User",
  "userType": "DRIVER"
}' localhost:50052 com.lastmile.user.UserService/RegisterUser
```

**Login:**
```bash
grpcurl -plaintext -d '{
  "email": "test@example.com",
  "password": "password123"
}' localhost:50052 com.lastmile.user.UserService/LoginUser
```

## Frontend Testing

**Note:** The frontend currently uses REST API calls. For production, you would need to:
1. Add a gRPC-Web gateway/proxy, OR
2. Convert frontend to use gRPC-Web client, OR
3. Add REST endpoints that internally call gRPC services

For now, to test the frontend with gRPC services, you'll need to add a REST-to-gRPC gateway or update the frontend API service layer.

1. Start the frontend: `cd frontend && npm start`
2. Navigate to `http://localhost:3000`
3. Use the Driver Dashboard to:
   - Enter driver ID, origin station, and destination
   - Click "Get Stations Along Route" to see metro stations
   - Register the route
4. Use the Rider Dashboard to:
   - Register a ride request
   - Find a match with a driver

## Key Features

### Driver Route Registration with Station Lookup
When a driver registers a route with a destination:
1. Driver Service calls Station Service with the destination
2. Station Service returns all metro stations along that route
3. Driver Service uses these stations for the route registration

**Example destinations:**
- North, South, East, West
- Central, Downtown
- Airport, Mall

## Service Ports (gRPC)

| Service | gRPC Port |
|---------|-----------|
| Station Service | 50051 |
| User Service | 50052 |
| Driver Service | 50053 |
| Rider Service | 50054 |
| Location Service | 50055 |
| Matching Service | 50056 |
| Trip Service | 50057 |
| Notification Service | 50058 |

## gRPC API Reference

All services use **Google RPC (gRPC)** for inter-service communication. Proto definitions are in the `proto/` directory:

- `proto/station.proto` - Station Service API
- `proto/user.proto` - User Service API
- `proto/driver.proto` - Driver Service API
- `proto/rider.proto` - Rider Service API
- `proto/location.proto` - Location Service API
- `proto/matching.proto` - Matching Service API
- `proto/trip.proto` - Trip Service API
- `proto/notification.proto` - Notification Service API

Proto files are automatically compiled during Maven build, generating Java stubs for both client and server implementations.

## Troubleshooting

### Services not starting
- Check MongoDB and Redis are running
- Verify environment variables are set correctly
- Check service logs: `kubectl logs <pod-name>`

### Connection issues
- Ensure services are accessible via service names in Kubernetes
- Check network policies if using them
- Verify port forwarding if accessing locally
- For gRPC: Ensure ports are correctly configured (50051-50058)
- Check that proto files are being compiled correctly during build

### Database connection errors
- Verify MongoDB is running and accessible
- Check connection strings in application.yml files
- Ensure database names are correct

## Project Structure

```
.
├── proto/
│   ├── station.proto
│   ├── user.proto
│   ├── driver.proto
│   ├── rider.proto
│   ├── location.proto
│   ├── matching.proto
│   ├── trip.proto
│   └── notification.proto
├── backend/
│   ├── station-service/
│   ├── user-service/
│   ├── driver-service/
│   ├── rider-service/
│   ├── location-service/
│   ├── matching-service/
│   ├── trip-service/
│   ├── notification-service/
│   └── Dockerfile.*
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   └── services/
│   └── package.json
└── k8s-springboot/
    ├── mongodb.yaml
    ├── redis.yaml
    └── *-service.yaml
```

## License

This project is for educational purposes.

