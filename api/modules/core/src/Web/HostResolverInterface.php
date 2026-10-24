<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

interface HostResolverInterface
{
    /**
     * Every IPv4 and IPv6 address the host resolves to; empty when it does not resolve.
     *
     * @return list<string>
     */
    public function resolve(string $host): array;
}
