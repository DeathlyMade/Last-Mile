# Start with the official Redis Alpine image (very small)
FROM redis:alpine

# Install Python and Requests library
RUN apk add --no-cache python3 py3-pip && \
    pip3 install requests redis --break-system-packages

WORKDIR /app

# Copy our scripts
COPY seed.py .
COPY start.sh .

# Make the shell script executable
RUN sed -i 's/\r$//' start.sh && chmod +x start.sh

# Override the default command to run our wrapper
CMD ["./start.sh"]