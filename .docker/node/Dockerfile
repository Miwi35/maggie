ARG NODE_VERSION=20

FROM node:${NODE_VERSION}-alpine

# Install system dependencies
RUN apk add --no-cache git

# Set working directory
WORKDIR /var/www/admin

# Create user matching host UID/GID (deletes existing node user if UID conflicts)
ARG UID=1000
ARG GID=1000
RUN deluser --remove-home node 2>/dev/null || true \
    && delgroup node 2>/dev/null || true \
    && addgroup -g ${GID} app \
    && adduser -u ${UID} -G app -s /bin/sh -D app \
    && chown -R app:app /var/www

USER app

# Expose Vite dev server port
EXPOSE 5173

# Default command: start Vite dev server with host binding
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
