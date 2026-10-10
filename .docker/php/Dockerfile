# syntax=docker/dockerfile:1
ARG PHP_VERSION=8.4

# =============================================================================
# Base stage - shared configuration
# =============================================================================
FROM public.ecr.aws/docker/library/php:${PHP_VERSION}-fpm-alpine AS base

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
COPY --from=public.ecr.aws/docker/library/composer:latest /usr/bin/composer /usr/bin/composer
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

# Scheduler for the cron pod (MAG-147). busybox crond ran the jobs as `app`
# with `>> /proc/1/fd/1`, a descriptor owned by root: the redirection failed
# and the command never started. supercronic runs as a plain user, logs each
# job's output and exit status on its own stdout, and needs no redirection.
ARG SUPERCRONIC_VERSION=v0.2.49
ARG TARGETARCH
RUN case "${TARGETARCH:-amd64}" in \
        amd64) sha256=a53ae236602c7338aba3fbaff40bda6300eae3b9fedb8261eb06cfe3724430c1 ;; \
        arm64) sha256=02aa0cb229ba09050cba6638059dadb9eedc2276632ea43d6a57a2f8c1629dd5 ;; \
        *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /usr/local/bin/supercronic \
        "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/supercronic-linux-${TARGETARCH:-amd64}" \
    && echo "${sha256}  /usr/local/bin/supercronic" | sha256sum -c - \
    && chmod 755 /usr/local/bin/supercronic

# Crontab of the scheduled tasks, run by `supercronic /etc/maggie/crontab`
COPY --link .docker/php/crontab /etc/maggie/crontab

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
# E2E stage — the deterministic test stack (MAG-94)
# =============================================================================
# Between dev and prod, deliberately:
#
#   - production php.ini, because several past regressions were prod-only and
#     an e2e run in development mode would hide exactly that class of bug;
#   - no Xdebug, which would slow every request in a suite whose whole point is
#     to run often, and which nobody attaches a debugger to in CI;
#   - source still bind-mounted, not copied, because the stack exists to test
#     the current worktree's code.
FROM base AS e2e

RUN mv "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

# libfaketime, for the e2e clock (MAG-234): `E2E_NOW` freezes php-fpm and the
# worker at one instant through LD_PRELOAD. Inert unless the compose file sets
# LD_PRELOAD, and linked at a path that does not depend on the architecture.
RUN apk add --no-cache libfaketime \
    && ln -s "$(find /usr/lib -name 'libfaketime.so.1' | head -n 1)" /usr/local/lib/libfaketime.so.1

USER app

EXPOSE 9000

CMD ["php-fpm"]

# =============================================================================
# Production stage
# =============================================================================
FROM base AS prod

# Set production environment
ENV APP_ENV=prod
ENV APP_DEBUG=0

# Use production PHP configuration
RUN mv "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

# Production OPcache settings
COPY --link .docker/php/conf.d/opcache.prod.ini $PHP_INI_DIR/conf.d/opcache.prod.ini

# Copy application source
COPY --link --chown=1000:1000 ./api .

# Install dependencies without dev packages
RUN composer install --no-dev --no-scripts --no-progress --optimize-autoloader

# Generate JWT keys for LexikJWTAuthenticationBundle
ARG JWT_PASSPHRASE=maggie-jwt-prod
RUN mkdir -p config/jwt \
    && openssl genpkey -algorithm RSA -out config/jwt/private.pem -pkeyopt rsa_keygen_bits:4096 -pass pass:${JWT_PASSPHRASE} \
    && openssl rsa -pubout -in config/jwt/private.pem -out config/jwt/public.pem -passin pass:${JWT_PASSPHRASE} \
    && chown app:app config/jwt/private.pem config/jwt/public.pem

# Run post-install scripts and warm up the cache: the pod starts with it ready,
# so the deploy never has to clear it (MAG-146). The build fails without it.
ENV JWT_PASSPHRASE=${JWT_PASSPHRASE}
RUN composer run-script --no-dev post-install-cmd \
    && bin/console cache:warmup \
    && test -d var/cache/prod

# Ensure var directory permissions
RUN chown -R app:app var

# Switch to non-root user
USER app

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD SCRIPT_NAME=/ping SCRIPT_FILENAME=/ping REQUEST_METHOD=GET cgi-fcgi -bind -connect 127.0.0.1:9000 || exit 1

EXPOSE 9000

# The deployed commit, which the Sentry SDK reports as the release (GlitchTip).
# Last, so a new commit does not invalidate the layers above.
ARG SENTRY_RELEASE=
ENV SENTRY_RELEASE=${SENTRY_RELEASE}

CMD ["php-fpm"]
