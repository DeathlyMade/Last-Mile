#!/bin/bash

# Function to build and load image if missing
ensure_image() {
    IMAGE_NAME=$1
    CONTEXT=$2
    DOCKERFILE=$3

    if [[ "$(docker images -q $IMAGE_NAME 2> /dev/null)" == "" ]]; then
        echo "Image $IMAGE_NAME not found. Building..."
        # Check if Dockerfile argument is provided
        if [ -z "$DOCKERFILE" ]; then
            docker build -t $IMAGE_NAME $CONTEXT
        else
            docker build -t $IMAGE_NAME -f $DOCKERFILE $CONTEXT
        fi
    else
        echo "Image $IMAGE_NAME found locally."
    fi

    # If minikube is running, load the image
    if command -v minikube &> /dev/null; then
        if minikube status &> /dev/null; then
             echo "Loading $IMAGE_NAME into Minikube..."
             minikube image load $IMAGE_NAME
        fi
    fi
}

echo "Checking and building images..."

# Build Redis (Custom)
ensure_image "lastmile/redis:latest" "../backend" ""

# Build Services
ensure_image "lastmile/station-service:latest" "../backend" "../backend/Dockerfile.station"
ensure_image "lastmile/user-service:latest" "../backend" "../backend/Dockerfile.user"
ensure_image "lastmile/driver-service:latest" "../backend" "../backend/Dockerfile.driver"
ensure_image "lastmile/rider-service:latest" "../backend" "../backend/Dockerfile.rider"
ensure_image "lastmile/location-service:latest" "../backend" "../backend/Dockerfile.location"
ensure_image "lastmile/matching-service:latest" "../backend" "../backend/Dockerfile.matching"
ensure_image "lastmile/trip-service:latest" "../backend" "../backend/Dockerfile.trip"
ensure_image "lastmile/notification-service:latest" "../backend" "../backend/Dockerfile.notification"

# Build Frontend
# Ensure proto generation if needed
if [ ! -d "../frontend/src/proto" ]; then
    echo "Generating protos for frontend..."
    (cd .. && ./generate-proto.sh)
fi
ensure_image "lastmile/new-frontend:latest" ".." "../frontend/Dockerfile"


# Deployment Steps
echo "Deploying to Kubernetes..."

# Create ConfigMap for proto.pb (Binary data support)
if [ -f "../proto.pb" ]; then
    kubectl create configmap proto-config --from-file=proto.pb=../proto.pb --dry-run=client -o yaml | kubectl apply -f -
else
    echo "Warning: ../proto.pb not found. Gateway might fail."
fi

# Apply Manifests
kubectl apply -f mongodb.yaml
kubectl apply -f redis.yaml
kubectl apply -f station-service.yaml
kubectl apply -f user-service.yaml
kubectl apply -f driver-service.yaml
kubectl apply -f rider-service.yaml
kubectl apply -f location-service.yaml
kubectl apply -f trip-service.yaml
kubectl apply -f notification-service.yaml
kubectl apply -f matching-service.yaml
kubectl apply -f matching-service-hpa.yaml
kubectl apply -f gateway.yaml
kubectl apply -f frontend.yaml

# Restart pods to pick up new images if they were already stuck
echo "Restarting deployments to pick up new images..."
kubectl rollout restart deployment/redis
kubectl rollout restart deployment/station-service
kubectl rollout restart deployment/user-service
kubectl rollout restart deployment/driver-service
kubectl rollout restart deployment/rider-service
kubectl rollout restart deployment/location-service
kubectl rollout restart deployment/trip-service
kubectl rollout restart deployment/notification-service
kubectl rollout restart deployment/matching-service
kubectl rollout restart deployment/frontend
kubectl rollout restart deployment/lastmile-gateway

echo "Deployment complete. Monitor status with 'kubectl get pods'."

echo "Waiting for deployments to roll out..."
kubectl rollout status deployment/frontend
kubectl rollout status deployment/lastmile-gateway

echo "Port forwarding services to localhost..."
echo "Frontend: http://localhost:3000"
echo "Gateway: http://localhost:8080"
echo "Press Ctrl+C to stop."

# Trap Ctrl+C to kill all background processes
trap "kill 0" SIGINT

kubectl port-forward svc/frontend 3000:3000 &
kubectl port-forward svc/lastmile-gateway 8080:8080 &

wait
