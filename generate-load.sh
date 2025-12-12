#!/bin/bash
echo "Starting load generator..."
kubectl run load-generator --image=curlimages/curl --restart=Never -- /bin/sh -c '
  while true; do 
    curl -s -X POST http://lastmile-gateway:8079/match \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ha-test-token" \
      -d "{\"rider_id\": \"test-rider\", \"metro_station\": \"Stn\", \"destination\": \"Dest\", \"arrival_time\": 0}" > /dev/null;
  done'

echo "Load generator started. Monitor HPA with: kubectl get hpa -w"
echo "To stop: kubectl delete pod load-generator"
