<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

use Symfony\Contracts\HttpClient\Exception\TimeoutExceptionInterface;
use Symfony\Contracts\HttpClient\Exception\TransportExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;
use Symfony\Contracts\HttpClient\ResponseInterface;

/**
 * Reads one public web page, with bounds: http(s) only, public addresses only (checked on
 * every redirect hop, and the connection is pinned to the address that was checked), a
 * redirect limit, a size limit and one deadline for the whole read. It carries nothing of
 * the user: no cookie, no header, just an identified user agent.
 */
class WebPageFetcher
{
    public const USER_AGENT = 'MaggieBot/1.0';
    private const MAX_REDIRECTS = 5;
    private const PUBLIC_PORTS = [80, 443];
    private const READABLE_TYPES = ['text/html', 'application/xhtml+xml', 'text/plain'];

    /**
     * @param list<string> $allowedHosts hosts exempt from the address check (the e2e stack's WireMock); empty in production
     */
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly HostResolverInterface $resolver,
        private readonly array $allowedHosts = [],
        private readonly int $maxBytes = 2_097_152,
        private readonly float $timeout = 10.0,
    ) {
    }

    /** @throws WebPageException */
    public function fetch(string $url): FetchedPage
    {
        $deadline = microtime(true) + $this->timeout;
        $current = trim($url);

        for ($hop = 0; $hop <= self::MAX_REDIRECTS; ++$hop) {
            $parts = $this->parse($current);
            $pin = $this->checkedAddress($parts['host'], $parts['port']);

            $options = [
                'max_redirects' => 0,
                'timeout' => $this->timeout,
                'max_duration' => max(0.5, $deadline - microtime(true)),
                'headers' => [
                    'User-Agent' => self::USER_AGENT,
                    'Accept' => 'text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5',
                    'Accept-Language' => 'fr,en;q=0.7',
                ],
            ];
            if (null !== $pin) {
                $options['resolve'] = [$parts['host'] => $pin];
            }

            try {
                $response = $this->httpClient->request('GET', $current, $options);

                $status = $response->getStatusCode();
                $headers = $response->getHeaders(false);

                if ($status >= 300 && $status < 400 && isset($headers['location'][0])) {
                    $response->cancel();
                    $current = $this->absolute($current, $headers['location'][0]);

                    continue;
                }
                if ($status >= 400) {
                    $response->cancel();

                    throw new WebPageException(sprintf('The page answered HTTP %d.', $status));
                }

                $type = strtolower(trim(explode(';', $headers['content-type'][0] ?? 'text/html')[0]));
                if (!\in_array($type, self::READABLE_TYPES, true)) {
                    $response->cancel();

                    throw new WebPageException(sprintf('Unsupported content type "%s": only web pages (HTML or text) can be read.', $type));
                }

                return new FetchedPage($current, $this->toUtf8($this->body($response, $headers, $deadline), $headers['content-type'][0] ?? ''));
            } catch (TimeoutExceptionInterface) {
                throw new WebPageException('The page took too long to answer (time limit exceeded).');
            } catch (TransportExceptionInterface) {
                throw new WebPageException('The site could not be reached.');
            }
        }

        throw new WebPageException(sprintf('Too many redirects (more than %d).', self::MAX_REDIRECTS));
    }

    /** @return array{host: string, port: int} */
    private function parse(string $url): array
    {
        $parts = parse_url($url);
        if (false === $parts || !isset($parts['scheme'])) {
            throw new WebPageException('Invalid URL: expected an absolute http(s) address.');
        }
        $scheme = strtolower($parts['scheme']);
        if (!\in_array($scheme, ['http', 'https'], true)) {
            throw new WebPageException(sprintf('Refused: the "%s" scheme is not allowed, only http and https.', $scheme));
        }
        if (!isset($parts['host'])) {
            throw new WebPageException('Invalid URL: expected an absolute http(s) address.');
        }
        if (isset($parts['user']) || isset($parts['pass'])) {
            throw new WebPageException('Refused: credentials in the URL are not allowed.');
        }

        return [
            'host' => strtolower(trim($parts['host'], '[]')),
            'port' => $parts['port'] ?? ('https' === $scheme ? 443 : 80),
        ];
    }

    /**
     * Throws when the host is not a public one; returns the address to connect to (null for an allowed host).
     *
     * @throws WebPageException
     */
    private function checkedAddress(string $host, int $port): ?string
    {
        if (\in_array($host, $this->allowedHosts, true)) {
            return null;
        }
        if (!\in_array($port, self::PUBLIC_PORTS, true)) {
            throw new WebPageException(sprintf('Refused: port %d is not allowed, only 80 and 443.', $port));
        }
        if ('localhost' === $host || str_ends_with($host, '.localhost')) {
            throw new WebPageException('Refused: the address is not public (localhost).');
        }

        if (false !== filter_var($host, FILTER_VALIDATE_IP)) {
            $addresses = [$host];
        } else {
            // 2130706433, 0x7f.1, 127.1: the system resolver reads these as IPv4, so they never go to DNS.
            if (1 === preg_match('/(^|\.)(0x[0-9a-f]*|\d+)$/i', $host)) {
                throw new WebPageException('Refused: the address is not a valid public host name.');
            }
            $addresses = $this->resolver->resolve($host);
            if ([] === $addresses) {
                throw new WebPageException(sprintf('The host "%s" does not exist (no DNS answer).', $host));
            }
        }

        foreach ($addresses as $address) {
            if (!PublicAddress::isPublic($address)) {
                throw new WebPageException('Refused: the address is not public (private, local or reserved network).');
            }
        }

        return $addresses[0];
    }

    private function absolute(string $base, string $location): string
    {
        if (1 === preg_match('#^[a-z][a-z0-9+.-]*:#i', $location)) {
            return $location;
        }
        $parts = parse_url($base);
        $origin = $parts['scheme'].'://'.$parts['host'].(isset($parts['port']) ? ':'.$parts['port'] : '');

        if (str_starts_with($location, '//')) {
            return $parts['scheme'].':'.$location;
        }
        if (str_starts_with($location, '/')) {
            return $origin.$location;
        }
        $directory = preg_replace('#/[^/]*$#', '/', $parts['path'] ?? '/');

        return $origin.$directory.$location;
    }

    /**
     * @param array<string, list<string>> $headers
     *
     * @throws WebPageException
     */
    private function body(ResponseInterface $response, array $headers, float $deadline): string
    {
        $declared = (int) ($headers['content-length'][0] ?? 0);
        if ($declared > $this->maxBytes) {
            $response->cancel();

            throw $this->tooBig();
        }

        $body = '';
        foreach ($this->httpClient->stream($response, max(0.1, $deadline - microtime(true))) as $chunk) {
            if ($chunk->isTimeout() || microtime(true) > $deadline) {
                $response->cancel();

                throw new WebPageException('The page took too long to answer (time limit exceeded).');
            }
            $body .= $chunk->getContent();
            if (\strlen($body) > $this->maxBytes) {
                $response->cancel();

                throw $this->tooBig();
            }
        }

        return $body;
    }

    private function tooBig(): WebPageException
    {
        return new WebPageException(sprintf('The page is too big (more than %d MB).', intdiv($this->maxBytes, 1_048_576)));
    }

    private function toUtf8(string $body, string $contentType): string
    {
        $charset = null;
        if (1 === preg_match('/charset=["\']?([\w-]+)/i', $contentType, $m)
            || 1 === preg_match('/<meta[^>]+charset=["\']?([\w-]+)/i', substr($body, 0, 4096), $m)) {
            $charset = strtoupper($m[1]);
        }
        if (null !== $charset && 'UTF-8' !== $charset && 'UTF8' !== $charset) {
            $converted = @mb_convert_encoding($body, 'UTF-8', $charset);
            if (\is_string($converted)) {
                return $converted;
            }
        }

        return mb_convert_encoding($body, 'UTF-8', 'UTF-8');
    }
}
