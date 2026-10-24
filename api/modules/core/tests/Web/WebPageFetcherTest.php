<?php

namespace Maggie\Core\Tests\Web;

use Maggie\Core\Web\HostResolverInterface;
use Maggie\Core\Web\WebPageException;
use Maggie\Core\Web\WebPageFetcher;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/** No test reaches the network: every request goes through a MockHttpClient, every name through a fake resolver. */
class WebPageFetcherTest extends TestCase
{
    /** @var list<array{url: string, options: array<string, mixed>}> */
    private array $requests = [];

    /** @var array<string, list<string>> */
    private array $dns = [
        'recettes.test' => ['93.184.216.34'],
        'www.recettes.test' => ['93.184.216.35'],
        'evil.test' => ['10.1.2.3'],
        'rebind.test' => ['93.184.216.36', '127.0.0.1'],
        'v6.test' => ['2606:2800:220:1:248:1893:25c8:1946'],
    ];

    /** @param callable(string, string): ResponseInterface $answer */
    private function fetcher(callable $answer, array $allowedHosts = [], int $maxBytes = 2_097_152, float $timeout = 10.0): WebPageFetcher
    {
        $http = new MockHttpClient(function (string $method, string $url, array $options) use ($answer): ResponseInterface {
            $this->requests[] = ['url' => $url, 'options' => $options];

            return $answer($method, $url);
        });
        $dns = $this->dns;
        $resolver = new class($dns) implements HostResolverInterface {
            /** @param array<string, list<string>> $dns */
            public function __construct(private array $dns)
            {
            }

            public function resolve(string $host): array
            {
                return $this->dns[$host] ?? [];
            }
        };

        return new WebPageFetcher($http, $resolver, $allowedHosts, $maxBytes, $timeout);
    }

    private static function html(string $body = '<html><body>ok</body></html>', int $status = 200, array $headers = []): MockResponse
    {
        return new MockResponse($body, ['http_code' => $status, 'response_headers' => $headers ?: ['content-type: text/html; charset=utf-8']]);
    }

    private static function redirect(string $to): MockResponse
    {
        return new MockResponse('', ['http_code' => 302, 'response_headers' => ['location: '.$to]]);
    }

    public function testReadsAPageAndSendsNothingOfTheUser(): void
    {
        $page = $this->fetcher(fn () => self::html('<p>Bonjour</p>'))->fetch('https://recettes.test/boeuf');

        self::assertSame('https://recettes.test/boeuf', $page->finalUrl);
        self::assertSame('<p>Bonjour</p>', $page->html);
        self::assertCount(1, $this->requests);

        $options = $this->requests[0]['options'];
        self::assertSame(0, $options['max_redirects']);
        self::assertContains('User-Agent: MaggieBot/1.0', $options['headers']);
        self::assertSame(['recettes.test' => '93.184.216.34'], $options['resolve'], 'the connection goes to the address that was checked');
        foreach ($options['headers'] as $header) {
            self::assertDoesNotMatchRegularExpression('/^(cookie|authorization|x-maggie)/i', $header);
        }
    }

    /** @return iterable<string, array{string, string}> */
    public static function refusedUrls(): iterable
    {
        yield 'localhost' => ['http://localhost', 'not public'];
        yield 'a localhost subdomain' => ['http://app.localhost/', 'not public'];
        yield 'loopback ip' => ['http://127.0.0.1', 'not public'];
        yield 'private ip' => ['http://10.0.0.1', 'not public'];
        yield 'cloud metadata' => ['http://169.254.169.254/latest/meta-data/', 'not public'];
        yield 'ipv6 loopback' => ['http://[::1]/', 'not public'];
        yield 'decimal ip' => ['http://2130706433/', 'valid public host'];
        yield 'hex ip' => ['http://0x7f.0.0.1/', 'valid public host'];
        yield 'short ip' => ['http://127.1/', 'valid public host'];
        yield 'a name that resolves to a private ip' => ['http://evil.test/', 'not public'];
        yield 'a name with one private address among public ones' => ['http://rebind.test/', 'not public'];
        yield 'a name that does not resolve' => ['http://nowhere.test/', 'does not exist'];
        yield 'file' => ['file:///etc/passwd', '"file" scheme'];
        yield 'ftp' => ['ftp://recettes.test/x', '"ftp" scheme'];
        yield 'gopher' => ['gopher://recettes.test/', '"gopher" scheme'];
        yield 'credentials' => ['https://user:pass@recettes.test/', 'credentials'];
        yield 'a port other than 80 and 443' => ['http://recettes.test:6379/', 'port 6379'];
        yield 'not a url' => ['pas une adresse', 'Invalid URL'];
        yield 'empty' => ['', 'Invalid URL'];
    }

    #[DataProvider('refusedUrls')]
    public function testRefusesAndNeverRequestsTheHost(string $url, string $reason): void
    {
        try {
            $this->fetcher(fn () => self::html())->fetch($url);
            self::fail('The URL should have been refused.');
        } catch (WebPageException $e) {
            self::assertStringContainsString($reason, $e->getMessage());
        }

        self::assertSame([], $this->requests);
    }

    public function testFollowsRedirectsAndReportsTheFinalUrl(): void
    {
        $page = $this->fetcher(fn (string $m, string $url) => match ($url) {
            'http://recettes.test/a' => self::redirect('https://www.recettes.test/b'),
            'https://www.recettes.test/b' => self::redirect('/c?x=1'),
            default => self::html('<p>fin</p>'),
        })->fetch('http://recettes.test/a');

        self::assertSame('https://www.recettes.test/c?x=1', $page->finalUrl);
        self::assertSame('<p>fin</p>', $page->html);
        self::assertSame(['93.184.216.35'], array_values($this->requests[2]['options']['resolve']));
    }

    #[DataProvider('redirectTargets')]
    public function testARedirectToAForbiddenTargetIsRefusedBeforeAnyRequestToIt(string $target): void
    {
        try {
            $this->fetcher(fn () => self::redirect($target))->fetch('https://recettes.test/a');
            self::fail('The redirect should have been refused.');
        } catch (WebPageException $e) {
            self::assertStringContainsString('Refused', $e->getMessage());
        }

        self::assertCount(1, $this->requests, 'only the first, public, host was requested');
    }

    /** @return iterable<string, array{string}> */
    public static function redirectTargets(): iterable
    {
        yield 'a private ip' => ['http://10.0.0.1/admin'];
        yield 'the metadata address' => ['http://169.254.169.254/'];
        yield 'a name that now resolves to a private ip' => ['http://evil.test/'];
        yield 'a file' => ['file:///etc/passwd'];
        yield 'localhost' => ['//localhost/'];
    }

    public function testTooManyRedirectsAreRefused(): void
    {
        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('Too many redirects');

        $this->fetcher(fn () => self::redirect('https://recettes.test/loop'))->fetch('https://recettes.test/loop');
    }

    public function testAPageTooBigForItsDeclaredLengthIsRefused(): void
    {
        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('too big');

        $this->fetcher(fn () => self::html(str_repeat('a', 2000), 200, ['content-type: text/html', 'content-length: 2000']), maxBytes: 1000)
            ->fetch('https://recettes.test/');
    }

    public function testAPageTooBigWithoutADeclaredLengthIsCutOff(): void
    {
        $chunks = (function () {
            for ($i = 0; $i < 100; ++$i) {
                yield str_repeat('a', 1000);
            }
        })();

        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('too big');

        $this->fetcher(fn () => new MockResponse($chunks, ['response_headers' => ['content-type: text/html']]), maxBytes: 10_000)
            ->fetch('https://recettes.test/');
    }

    public function testAPageThatStallsIsRefusedOnTheTimeLimit(): void
    {
        $stalling = (function () {
            yield 'début';
            yield '';
            yield 'fin';
        })();

        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('time limit');

        $this->fetcher(fn () => new MockResponse($stalling, ['response_headers' => ['content-type: text/html']]), timeout: 0.3)
            ->fetch('https://recettes.test/');
    }

    public function testAnUnreachableSiteIsReportedAsSuch(): void
    {
        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('could not be reached');

        $this->fetcher(fn () => new MockResponse('', ['error' => 'Connection refused']))->fetch('https://recettes.test/');
    }

    public function testAnErrorStatusIsReportedWithItsCode(): void
    {
        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('HTTP 404');

        $this->fetcher(fn () => self::html('Introuvable', 404))->fetch('https://recettes.test/nope');
    }

    public function testOnlyWebPagesAreRead(): void
    {
        $this->expectException(WebPageException::class);
        $this->expectExceptionMessage('Unsupported content type "application/pdf"');

        $this->fetcher(fn () => self::html('%PDF', 200, ['content-type: application/pdf']))->fetch('https://recettes.test/a.pdf');
    }

    public function testALegacyCharsetIsConvertedToUtf8(): void
    {
        $body = file_get_contents(__DIR__.'/fixtures/latin1-page.html');

        $page = $this->fetcher(fn () => self::html($body, 200, ['content-type: text/html; charset=ISO-8859-1']))->fetch('https://recettes.test/');

        self::assertStringContainsString('Crêpes à la bière', $page->html);
    }

    public function testACharsetDeclaredOnlyInTheMarkupIsHonoured(): void
    {
        $body = file_get_contents(__DIR__.'/fixtures/latin1-page.html');

        $page = $this->fetcher(fn () => self::html($body, 200, ['content-type: text/html']))->fetch('https://recettes.test/');

        self::assertStringContainsString('Crêpes à la bière', $page->html);
    }

    public function testACharsetThePageInventedDoesNotBreakTheRead(): void
    {
        $page = $this->fetcher(fn () => self::html('<html><body>Crêpes</body></html>', 200, ['content-type: text/html; charset=x-bogus']))->fetch('https://recettes.test/');

        self::assertStringContainsString('Crêpes', $page->html);
    }

    public function testAnIpLiteralIsCheckedButNotPinned(): void
    {
        $this->fetcher(fn () => self::html())->fetch('https://93.184.216.34/');

        self::assertArrayNotHasKey('resolve', $this->requests[0]['options']);
    }

    public function testAnIpv6HostIsPinnedToo(): void
    {
        $this->fetcher(fn () => self::html())->fetch('https://v6.test/');

        self::assertSame(['v6.test' => '2606:2800:220:1:248:1893:25c8:1946'], $this->requests[0]['options']['resolve']);
    }

    public function testAnAllowedHostSkipsTheAddressCheck(): void
    {
        $this->dns['wiremock'] = ['172.20.0.5'];

        $page = $this->fetcher(fn () => self::html('<p>fixture</p>'), ['wiremock'])->fetch('http://wiremock:8080/pages/recette');

        self::assertSame('<p>fixture</p>', $page->html);
        self::assertArrayNotHasKey('resolve', $this->requests[0]['options']);
    }

    public function testAnAllowedHostDoesNotOpenOtherPrivateHosts(): void
    {
        $this->expectException(WebPageException::class);

        $this->fetcher(fn () => self::redirect('http://10.0.0.1/'), ['wiremock'])->fetch('http://wiremock:8080/pages/recette');
    }
}
