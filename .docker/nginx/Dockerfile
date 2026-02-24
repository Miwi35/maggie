# Global ARG for PHP image (used in prod stage to copy public assets)
ARG PHP_IMAGE=maggie-php:latest

# =============================================================================
# Development stage (default)
# =============================================================================
FROM nginx:alpine AS dev

# Remove default config
RUN rm /etc/nginx/conf.d/default.conf

# Copy nginx configuration templates
COPY .docker/nginx/templates/ /etc/nginx/templates/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]

# =============================================================================
# Admin build stage — compile React SPA
# =============================================================================
FROM node:22-alpine AS admin-build

WORKDIR /build

COPY admin/package.json admin/package-lock.json ./
RUN npm ci

COPY admin/ .

ARG MERCURE_PUBLIC_URL=/.well-known/mercure
ENV VITE_API_URL=/api
ENV VITE_MERCURE_PUBLIC_URL=${MERCURE_PUBLIC_URL}
RUN npm run build

# =============================================================================
# Production stage — nginx with baked-in admin + API public assets
# =============================================================================
FROM ${PHP_IMAGE} AS php-source

FROM nginx:alpine AS prod

# Remove default config
RUN rm /etc/nginx/conf.d/default.conf

# Copy nginx configuration templates
COPY .docker/nginx/templates/ /etc/nginx/templates/

# Copy built admin SPA
COPY --from=admin-build /build/dist/ /var/www/admin/

# Copy API public directory (index.php + bundles from composer assets:install)
COPY --from=php-source /var/www/api/public/ /var/www/api/public/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
