#!/bin/bash

# Usage: ./deploy.sh [SERVER_IP] [SERVER_USER]
# Default SERVER_USER is root

SERVER_IP=$1
SERVER_USER=${2:-root}

if [ -z "$SERVER_IP" ]; then
  echo "Usage: ./deploy.sh [SERVER_IP] [SERVER_USER]"
  exit 1
fi

echo "Deploying to $SERVER_USER@$SERVER_IP..."

# 1. Save the image and pipe it to the remote server to load it
echo "Transferring Docker image (this may take a while)..."
docker save clashproxy:latest | ssh -C $SERVER_USER@$SERVER_IP "docker load"

# 2. Stop and remove existing container
echo "Stopping existing container..."
ssh $SERVER_USER@$SERVER_IP "docker stop clashproxy || true && docker rm clashproxy || true"

# 3. Run the new container
echo "Starting new container..."
ssh $SERVER_USER@$SERVER_IP "docker run -d --restart unless-stopped --name clashproxy -p 8080:8080 clashproxy:latest"

echo "Deployment complete! Application running on http://$SERVER_IP:8080"
