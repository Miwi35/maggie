<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Mcp\Tool\ReadWebPageTool;
use Maggie\Core\Web\HostResolverInterface;
use Maggie\Core\Web\WebPageExtractor;
use Maggie\Core\Web\WebPageFetcher;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/** Nothing reaches the network: pages are recorded fixtures served by a MockHttpClient. */
class ReadWebPageToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    /** @var list<string> */
    private array $requested = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->requested = [];
    }

    /** @param array<string, string|ResponseInterface> $pages url => fixture file name or response */
    private function tool(array $pages): ReadWebPageTool
    {
        $http = new MockHttpClient(function (string $method, string $url) use ($pages): ResponseInterface {
            $this->requested[] = $url;
            $page = $pages[$url] ?? null;

            return $page instanceof ResponseInterface
                ? $page
                : new MockResponse(
                    null === $page ? 'Introuvable' : file_get_contents(__DIR__.'/../Web/fixtures/'.$page),
                    ['http_code' => null === $page ? 404 : 200, 'response_headers' => ['content-type: text/html; charset=utf-8']],
                );
        });
        $resolver = new class implements HostResolverInterface {
            public function resolve(string $host): array
            {
                return ['93.184.216.34'];
            }
        };

        return new ReadWebPageTool(
            self::getContainer()->get(McpUserContext::class),
            new WebPageFetcher($http, $resolver),
            new WebPageExtractor(),
        );
    }

    /** @return array<string, mixed> */
    private function call(ReadWebPageTool $tool, string $url): array
    {
        return json_decode($tool($url), true, 512, JSON_THROW_ON_ERROR);
    }

    private function loggedIn(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $this->loginFixtureUser();
    }

    public function testWithoutAUserBoundReturnsAnErrorAndReadsNothing(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');

        $result = $this->call($this->tool(['https://www.marmiton.test/boeuf' => 'marmiton-boeuf-bourguignon.html']), 'https://www.marmiton.test/boeuf');

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
        self::assertSame([], $this->requested);
    }

    public function testAnInvalidUrlIsAnErrorAndReadsNothing(): void
    {
        $this->loggedIn();

        $result = $this->call($this->tool([]), 'pas une adresse');

        self::assertStringContainsString('Invalid URL', $result['error']);
        self::assertSame([], $this->requested);
    }

    public function testARecipePageGivesTheContract(): void
    {
        $this->loggedIn();
        $url = 'https://www.marmiton.test/boeuf';

        $result = $this->call($this->tool([$url => 'marmiton-boeuf-bourguignon.html']), $url);

        self::assertSame(['url', 'finalUrl', 'title', 'text', 'structured', 'fetchedAt'], array_keys($result));
        self::assertSame($url, $result['url']);
        self::assertSame($url, $result['finalUrl']);
        self::assertSame('Boeuf bourguignon : la vraie recette | Marmiton', $result['title']);
        self::assertStringContainsString('150 g de lardons', $result['text']);
        self::assertSame('Recipe', $result['structured'][0]['@type']);
        self::assertCount(6, $result['structured'][0]['recipeIngredient']);
        self::assertCount(3, $result['structured'][0]['recipeInstructions']);
        self::assertNotFalse(\DateTimeImmutable::createFromFormat(\DATE_ATOM, $result['fetchedAt']));
    }

    public function testAnEventPageGivesItsEvent(): void
    {
        $this->loggedIn();
        $url = 'https://www.liberte.test/concert';

        $result = $this->call($this->tool([$url => 'event-concert.html']), $url);

        self::assertSame('Event', $result['structured'][0]['@type']);
        self::assertSame('2026-11-14T20:30:00+01:00', $result['structured'][0]['startDate']);
    }

    public function testAProductPageGivesItsProduct(): void
    {
        $this->loggedIn();
        $url = 'https://www.cafes.test/arabica';

        $result = $this->call($this->tool([$url => 'product-coffee.html']), $url);

        self::assertSame(['Organization', 'Product'], array_column($result['structured'], '@type'));
        self::assertSame('EUR', $result['structured'][1]['offers']['priceCurrency']);
    }

    public function testARefusedPageIsAnErrorWithItsReason(): void
    {
        $this->loggedIn();

        $private = $this->call($this->tool([]), 'http://169.254.169.254/latest/meta-data/');
        $missing = $this->call($this->tool([]), 'https://www.marmiton.test/absent');

        self::assertStringContainsString('not public', $private['error']);
        self::assertArrayNotHasKey('text', $private);
        self::assertStringContainsString('HTTP 404', $missing['error']);
        self::assertSame(['https://www.marmiton.test/absent'], $this->requested, 'the metadata address was never requested');
    }

    public function testAPrivateRedirectIsRefusedWithoutRequestingIt(): void
    {
        $this->loggedIn();
        $redirect = new MockResponse('', ['http_code' => 301, 'response_headers' => ['location: http://10.0.0.1/']]);

        $result = $this->call($this->tool(['https://short.test/x' => $redirect]), 'https://short.test/x');

        self::assertStringContainsString('not public', $result['error']);
        self::assertSame(['https://short.test/x'], $this->requested);
    }

    public function testIsRegisteredAsAnMcpToolWithItsDependenciesWired(): void
    {
        self::assertInstanceOf(ReadWebPageTool::class, self::getContainer()->get(ReadWebPageTool::class));
    }
}
