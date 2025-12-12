# Build stage
FROM node:18-bullseye AS build

# Install protoc
RUN apt-get update && apt-get install -y protobuf-compiler

WORKDIR /app

# Copy package files
COPY frontend/package*.json ./

# Install dependencies
# Install dependencies (ignoring lockfile to resolve platform mismatch)
RUN rm -f package-lock.json && npm install

# Copy source code
COPY frontend/ .

# Accept build argument for Google Maps API key
ARG VITE_APP_GOOGLE_MAPS_API_KEY
ENV VITE_APP_GOOGLE_MAPS_API_KEY=$VITE_APP_GOOGLE_MAPS_API_KEY
# Force replace with LITERAL string to ensure no expansion issues
RUN find src -name "*.tsx" -exec sed -i "s/import.meta.env.VITE_APP_GOOGLE_MAPS_API_KEY/\"AIzaSyAirLVe4aBHUqN5CaZjf7eBvXMAjTfbHI8\"/g" {} +
# VERIFY the replacement happened (will fail build if not found)
RUN grep -r "AIzaSy" src/components/RiderDashboard.tsx || { echo "API Key replacement FAILED"; exit 1; }

# Generate Protos
RUN mkdir -p src/proto/google/api

# 1. Generate Google APIs
RUN protoc -I=/app/googleapis \
  /app/googleapis/google/api/annotations.proto \
  /app/googleapis/google/api/http.proto \
  --plugin=protoc-gen-ts="./node_modules/.bin/protoc-gen-ts" \
  --plugin=protoc-gen-grpc-web="./node_modules/.bin/protoc-gen-grpc-web" \
  --js_out=import_style=commonjs,binary:src/proto \
  --ts_out=src/proto \
  --grpc-web_out=import_style=typescript,mode=grpcwebtext:src/proto

# 2. Generate Service Protos
RUN protoc -I=/app/backend-proto -I=/app/googleapis \
  --plugin=protoc-gen-ts="./node_modules/.bin/protoc-gen-ts" \
  --plugin=protoc-gen-grpc-web="./node_modules/.bin/protoc-gen-grpc-web" \
  --js_out=import_style=commonjs,binary:src/proto \
  --ts_out=src/proto \
  --grpc-web_out=import_style=commonjs,mode=grpcwebtext:src/proto \
  /app/backend-proto/*.proto

# Build the Vite app
RUN npm run build

# Production stage
FROM nginx:alpine

# Copy built assets from build stage
COPY --from=build /app/build /usr/share/nginx/html

# Copy nginx configuration for SPA routing
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Expose port 80
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
