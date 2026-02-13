# syntax=docker/dockerfile:1
ARG PHP_VERSION=8.4

# =============================================================================
# Base stage - shared configuration
# =============================================================================
FROM php:${PHP_VERSION}-fpm-alpine AS base

# Install system dependencies
RUN apk add --no-cache \
    acl \
    fcgi \
    file \
    gettext \
    git \
    curl \
    libpng-dev \
    libjpeg-turbo-dev \
    freetype-dev \
    libzip-dev \
    zip \
    unzip \
    icu-dev \
    postgresql-dev \
    linux-headers \
    su-exec \
    rabbitmq-c-dev \
    $PHPIZE_DEPS

# Configure and install PHP extensions
RUN docker-php-ext-configure gd --with-freetype --with-jpeg \
    && docker-php-ext-install -j$(nproc) \
        pdo \
        pdo_pgsql \
        pgsql \
        gd \
        intl \
        zip \
        opcache \
        bcmath

# Install APCu for caching
RUN pecl install apcu \
    && docker-php-ext-enable apcu

# Install AMQP for RabbitMQ messaging
RUN pecl install amqp \
    && docker-php-ext-enable amqp

# Install Composer
COPY --from=composer:latest /usr/bin/composer /usr/bin/composer
ENV COMPOSER_ALLOW_SUPERUSER=1

# Create custom PHP configuration
COPY --link .docker/php/conf.d/app.ini $PHP_INI_DIR/conf.d/

# Set working directory
WORKDIR /var/www/api

# Create non-root user matching common host UID/GID
ARG UID=1000
ARG GID=1000
RUN addgroup -g ${GID} app \
    && adduser -u ${UID} -G app -s /bin/sh -D app

# Create necessary directories with proper permissions
RUN mkdir -p var/cache var/log var/sessions \
    && chown -R app:app /var/www

# Copy entrypoint script
COPY --link --chmod=755 .docker/php/docker-entrypoint.sh /usr/local/bin/docker-entrypoint

ENTRYPOINT ["docker-entrypoint"]

# =============================================================================
# Development stage
# =============================================================================
FROM base AS dev

# Use development PHP configuration
RUN mv "$PHP_INI_DIR/php.ini-development" "$PHP_INI_DIR/php.ini"

# Install Xdebug for development
RUN pecl install xdebug \
    && docker-php-ext-enable xdebug

# Xdebug configuration
COPY --link .docker/php/conf.d/xdebug.ini $PHP_INI_DIR/conf.d/

# Switch to non-root user
USER app

EXPOSE 9000

CMD ["php-fpm"]

# =============================================================================
# Production stage
# =============================================================================
FROM base AS prod

# Use production PHP configuration
RUN mv "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

# Production OPcache settings
COPY --link .docker/php/conf.d/opcache.prod.ini $PHP_INI_DIR/conf.d/opcache.prod.ini

# Copy application source
COPY --link --chown=1000:1000 ./api .

# Install dependencies without dev packages
RUN composer install --no-dev --no-scripts --no-progress --optimize-autoloader

# Run post-install scripts and warm up cache
RUN composer run-script post-install-cmd \
    && bin/console cache:warmup

# Ensure var directory permissions
RUN chown -R app:app var

# Switch to non-root user
USER app

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD SCRIPT_NAME=/ping SCRIPT_FILENAME=/ping REQUEST_METHOD=GET cgi-fcgi -bind -connect 127.0.0.1:9000 || exit 1

EXPOSE 9000

CMD ["php-fpm"]
