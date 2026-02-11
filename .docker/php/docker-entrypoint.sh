#!/bin/sh
set -e

# Fix permissions for var directory if running as root
if [ "$(id -u)" = "0" ]; then
    # Set ACLs on var directory for proper Symfony cache/log handling
    if [ -d "var" ]; then
        setfacl -R -m u:app:rwX -m u:www-data:rwX var 2>/dev/null || true
        setfacl -dR -m u:app:rwX -m u:www-data:rwX var 2>/dev/null || true
    fi

    # Create var subdirectories if they don't exist
    mkdir -p var/cache var/log var/sessions
    chown -R app:app var

    # If first arg is php-fpm, switch to app user
    if [ "$1" = "php-fpm" ]; then
        exec su-exec app "$@"
    fi
fi

# First arg is `-f` or `--some-option`
if [ "${1#-}" != "$1" ]; then
    set -- php-fpm "$@"
fi

exec "$@"
