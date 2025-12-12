#!/bin/bash
set -e

# Configuration
USER_HOME="/Users/daksh15"
TMP_CERTS="/tmp/certs"
TMP_KUBECONFIG="/tmp/kubeconfig"
ANSIBLE_BUILD_FILE="$(pwd)/ansible/roles/lastmile_deploy/tasks/build.yml"

echo "=========================================="
echo "    Jenkins Environment Setup Script"
echo "=========================================="

# Ensure script is run with sudo if accessing restricted files
if [ "$EUID" -ne 0 ]; then
  echo "Please run this script with sudo."
  exit 1
fi

# 1. Setup Certificates
echo "[1/4] Setting up Docker certificates in $TMP_CERTS..."
mkdir -p $TMP_CERTS
cp "$USER_HOME/.minikube/certs/ca.pem" "$TMP_CERTS/"
cp "$USER_HOME/.minikube/certs/cert.pem" "$TMP_CERTS/"
cp "$USER_HOME/.minikube/certs/key.pem" "$TMP_CERTS/"

# Ensure readable by others (Jenkins)
chmod -R 644 $TMP_CERTS
chmod 755 $TMP_CERTS
echo "      Certificates copied and permissions set."

# 2. Setup Kubeconfig
echo "[2/4] Generating flattened Kubeconfig in $TMP_KUBECONFIG..."
# Use the user's config as source
export KUBECONFIG="$USER_HOME/.kube/config"
# Run kubectl as the original user to avoid permission issues with ~/.kube/config if root can't read it (though root usually can)
# However, kubectl config view --flatten writes to stdout
rm -f "$TMP_KUBECONFIG"
kubectl config view --flatten > "$TMP_KUBECONFIG"
chmod 644 "$TMP_KUBECONFIG"
echo "      Kubeconfig generated."

# 3. Detect Minikube Docker Port
echo "[3/4] Detecting Minikube Docker daemon port..."
# Get the host port mapped to container port 2376/tcp
DOCKER_PORT=$(docker inspect minikube --format='{{(index (index .NetworkSettings.Ports "2376/tcp") 0).HostPort}}')

if [ -z "$DOCKER_PORT" ]; then
    echo "Error: Could not detect Minikube Docker port. Is Minikube running?"
    exit 1
fi
echo "      Found Docker port: $DOCKER_PORT"

# 4. Update Ansible Playbook
echo "[4/4] Updating Ansible playbook with new port..."
if [ -f "$ANSIBLE_BUILD_FILE" ]; then
    # Replace the port in DOCKER_HOST line
    # Matches export DOCKER_HOST="tcp://127.0.0.1:<digits>"
    sed -i '' "s|export DOCKER_HOST=\"tcp://127.0.0.1:[0-9]*\"|export DOCKER_HOST=\"tcp://127.0.0.1:$DOCKER_PORT\"|g" "$ANSIBLE_BUILD_FILE"
    echo "      Updated $ANSIBLE_BUILD_FILE"
else
    echo "Error: Ansible build file not found at $ANSIBLE_BUILD_FILE"
    exit 1
fi

# 5. Enable Minikube Addons
echo "[5/5] Enabling Minikube Addons (ingress, metrics-server)..."
# Determine the user who invokes sudo
REAL_USER=${SUDO_USER:-$USER}
if [ "$REAL_USER" == "root" ]; then
    echo "Warning: Cannot determine real user to run Minikube commands. Skipping addons."
else
    echo "      Running as user: $REAL_USER"
    su - $REAL_USER -c "minikube addons enable ingress"
    su - $REAL_USER -c "minikube addons enable metrics-server"
fi

echo "=========================================="
echo "    Setup Complete!"
echo "=========================================="
