<?php

declare(strict_types=1);

namespace Maggie\Calendar\Google;

use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Entity\User;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Google People API (MAG-664): the addresses of the user's Google account,
 * readable once they granted `user.addresses.read` at sign-in.
 */
class GooglePeopleClient
{
    private const string DEFAULT_ROOT = 'https://people.googleapis.com/';
    private const int TIMEOUT_SECONDS = 5;

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly GoogleCalendarApiClient $googleClient,
        /** Empty except in the e2e stack, where it is the WireMock stand-in (same root as Calendar). */
        private readonly string $googleApiBaseUrl = '',
    ) {
    }

    /**
     * The address Google has as « Domicile »; failing that, the first primary
     * one. Null when the account has none, or the user has no Google token.
     *
     * @throws \Throwable the scope was refused, Google is unreachable or answered an error
     */
    public function homeAddress(User $user): ?string
    {
        $accessToken = $this->googleClient->freshAccessToken($user);
        if (null === $accessToken) {
            return null;
        }

        $root = '' !== $this->googleApiBaseUrl ? rtrim($this->googleApiBaseUrl, '/').'/' : self::DEFAULT_ROOT;
        $data = $this->httpClient->request('GET', $root.'v1/people/me', [
            'query' => ['personFields' => 'addresses'],
            'auth_bearer' => $accessToken,
            'timeout' => self::TIMEOUT_SECONDS,
        ])->toArray();

        $addresses = array_values(array_filter(
            \is_array($data['addresses'] ?? null) ? $data['addresses'] : [],
            static fn (mixed $address): bool => \is_array($address) && '' !== self::text($address),
        ));

        foreach ($addresses as $address) {
            if ('home' === strtolower((string) ($address['type'] ?? ''))) {
                return self::text($address);
            }
        }
        foreach ($addresses as $address) {
            if (true === ($address['metadata']['primary'] ?? false)) {
                return self::text($address);
            }
        }

        return null;
    }

    /** @param array<string, mixed> $address */
    private static function text(array $address): string
    {
        $formatted = trim((string) ($address['formattedValue'] ?? ''));
        if ('' !== $formatted) {
            return preg_replace('/\s*\R\s*/', ', ', $formatted) ?? $formatted;
        }

        $parts = array_filter(
            array_map(static fn (string $key): string => trim((string) ($address[$key] ?? '')), ['streetAddress', 'postalCode', 'city', 'country']),
            static fn (string $part): bool => '' !== $part,
        );

        return implode(', ', $parts);
    }
}
