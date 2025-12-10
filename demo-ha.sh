#!/bin/bash

# Ensure we have at least 2 replicas
REPLICAS=$(kubectl get deployment matching-service -o=jsonpath='{.status.readyReplicas}')
if [ "$REPLICAS" -lt 2 ]; then
    echo "Scaling up matching-service to 2 replicas for HA demo..."
    # We temporarily increase minReplicas on HPA to force 2 pods, otherwise HPA might fight us
    kubectl patch hpa matching-service-hpa -p '{"spec":{"minReplicas":2}}'
    echo "Waiting for scale up..."
    kubectl rollout status deployment/matching-service
fi

echo "Injecting dummy authentication token into Redis..."
REDIS_POD=$(kubectl get pods -l app=redis -o jsonpath="{.items[0].metadata.name}")
kubectl exec $REDIS_POD -- redis-cli set token:ha-test-token ha-test-user
echo "Token injected."

echo "Starting traffic loop (hitting Gateway)..."
kubectl run ha-test-client --image=curlimages/curl --restart=Never -- /bin/sh -c '
  while true; do 
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://lastmile-gateway:8080/match \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ha-test-token" \
      -d "{\"rider_id\": \"ha-test\", \"metro_station\": \"A\", \"destination\": \"B\", \"arrival_time\": 0}")
    if [ "$http_code" == "200" ]; then
        echo "$(date): Request Successful (200)"
    else
        echo "$(date): Request Failed ($http_code)"
    fi
    sleep 0.5
  done' &

# Wait for pod to be created
echo "Waiting for traffic generator pod to be created..."
while ! kubectl get pod ha-test-client &> /dev/null; do
    sleep 1
done

# Wait for pod to be ready
echo "Waiting for traffic generator to be running..."
kubectl wait --for=condition=Ready pod/ha-test-client --timeout=90s

echo "Tailing logs from traffic generator (Press Ctrl+C to stop early)..."

# Stream logs in background
kubectl logs -f ha-test-client &
LOG_PID=$!

sleep 5

# Find a victim pod
POD_TO_KILL=$(kubectl get pods -l app=matching-service -o name | head -n 1)
echo "----------------------------------------------------------------"
echo "SIMULATING FAILURE: Deleting $POD_TO_KILL in 3 seconds..."
echo "----------------------------------------------------------------"
sleep 3
kubectl delete $POD_TO_KILL &

# Wait a bit to observe
sleep 10
echo "----------------------------------------------------------------"
echo "Test Complete. If you saw 'Request Successful' continue during the deletion, HA works."
echo "----------------------------------------------------------------"

# Cleanup
kill $LOG_PID
kubectl delete pod ha-test-client
# Reset HPA minReplicas
kubectl patch hpa matching-service-hpa -p '{"spec":{"minReplicas":1}}'
