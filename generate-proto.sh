#!/bin/bash

# Create directory for generated files
mkdir -p frontend/src/proto
# Clean previous generated files to avoid mixed module formats
rm -rf frontend/src/proto/*

# Check for protoc
if ! command -v protoc &> /dev/null; then
    echo "Error: protoc is not installed. Please install Protocol Buffers compiler."
    exit 1
fi

# Check for plugins
# You can install them via npm: npm install --save-dev protoc-gen-ts protoc-gen-grpc-web
# OR download binaries.
# This script assumes they are available in the path or node_modules.

PROTOC_GEN_TS_PATH="./frontend/node_modules/.bin/protoc-gen-ts"
PROTOC_GEN_GRPC_WEB_PATH="./frontend/node_modules/.bin/protoc-gen-grpc-web"

if [ ! -f "$PROTOC_GEN_TS_PATH" ]; then
    if command -v protoc-gen-ts &> /dev/null; then
        PROTOC_GEN_TS_PATH=$(command -v protoc-gen-ts)
    else
        echo "Error: protoc-gen-ts not found. Run 'npm install --save-dev protoc-gen-ts' in frontend directory."
        # exit 1
    fi
fi

if [ ! -f "$PROTOC_GEN_GRPC_WEB_PATH" ]; then
    if command -v protoc-gen-grpc-web &> /dev/null; then
        PROTOC_GEN_GRPC_WEB_PATH=$(command -v protoc-gen-grpc-web)
    else
        echo "Error: protoc-gen-grpc-web not found. Run 'npm install --save-dev protoc-gen-grpc-web' in frontend directory."
        # exit 1
    fi
fi

echo "Generating protos..."

# Generate Google APIs protos
echo "Compiling Google APIs protos..."
mkdir -p frontend/src/proto/google/api
protoc -I=googleapis \
  googleapis/google/api/annotations.proto \
  googleapis/google/api/http.proto \
  --plugin=protoc-gen-ts="$PROTOC_GEN_TS_PATH" \
  --plugin=protoc-gen-grpc-web="$PROTOC_GEN_GRPC_WEB_PATH" \
  --js_out=import_style=commonjs,binary:frontend/src/proto \
  --ts_out=frontend/src/proto \
  --grpc-web_out=import_style=typescript,mode=grpcwebtext:frontend/src/proto

# Generate FileDescriptorSet for Envoy
echo "Generating proto.pb for Envoy..."
protoc -I=backend/proto -I=googleapis \
  --include_imports \
  --include_source_info \
  --descriptor_set_out=proto.pb \
  backend/proto/*.proto

for proto_file in backend/proto/*.proto; do
  echo "Compiling $proto_file..."
  # Note: Adjust flags based on the specific plugins you are using.
  # The following assumes standard grpc-web and ts plugins.
  
  protoc -I=backend/proto -I=googleapis \
    "$proto_file" \
    --plugin=protoc-gen-ts="$PROTOC_GEN_TS_PATH" \
    --plugin=protoc-gen-grpc-web="$PROTOC_GEN_GRPC_WEB_PATH" \
        --js_out=import_style=commonjs,binary:frontend/src/proto \
        --ts_out=frontend/src/proto \
        --grpc-web_out=import_style=commonjs,mode=grpcwebtext:frontend/src/proto
done

echo "Proto generation complete."

