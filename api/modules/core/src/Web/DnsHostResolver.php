<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

class DnsHostResolver implements HostResolverInterface
{
    public function resolve(string $host): array
    {
        $addresses = [];
        foreach (@dns_get_record($host, DNS_A | DNS_AAAA) ?: [] as $record) {
            $address = $record['ip'] ?? $record['ipv6'] ?? null;
            if (\is_string($address)) {
                $addresses[] = $address;
            }
        }

        if ([] === $addresses) {
            $addresses = @gethostbynamel($host) ?: [];
        }

        return array_values(array_unique($addresses));
    }
}
