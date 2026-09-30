<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use App\Kernel;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Routing\RouteCollection;
use Symfony\Component\Routing\RouterInterface;

/**
 * MAG-94 asks for the test login's absence from production to be tested
 * explicitly, not asserted in prose. This is that test.
 *
 * It boots a real kernel per environment and asks its router, because the
 * router is what actually decides whether a request can reach the controller.
 * A test that merely checked a class annotation would still pass the day
 * someone adds the route to a config file.
 */
final class E2eLoginRouteAbsenceTest extends TestCase
{
    private const ROUTE = 'auth_e2e_login';

    protected function setUp(): void
    {
        // The route's own token, needed for the e2e kernel to compile. Set for
        // every environment below, so the absence cases prove something about
        // the wiring rather than about a missing variable.
        $_ENV['E2E_LOGIN_TOKEN'] = $_SERVER['E2E_LOGIN_TOKEN'] = 'token-for-the-absence-test';
    }

    #[DataProvider('environmentsWithoutTheRoute')]
    public function testRouteDoesNotExistOutsideE2e(string $environment): void
    {
        self::assertNull(
            $this->routesFor($environment)->get(self::ROUTE),
            sprintf('The e2e test login must not be routable in the "%s" environment.', $environment),
        );
    }

    #[DataProvider('environmentsWithoutTheRoute')]
    public function testNoRouteMatchesAnE2ePathOutsideE2e(string $environment): void
    {
        // Belt to the previous test's braces: a route added under another name
        // would slip past a name lookup.
        foreach ($this->routesFor($environment) as $name => $route) {
            self::assertStringNotContainsString(
                '/auth/e2e',
                $route->getPath(),
                sprintf('Route "%s" exposes an e2e path in the "%s" environment.', $name, $environment),
            );
        }
    }

    public function testRouteExistsInE2e(): void
    {
        // The mirror of the assertions above: without it, they would also pass
        // if the controller had simply been deleted.
        $route = $this->routesFor('e2e')->get(self::ROUTE);

        self::assertNotNull($route, 'The e2e test login must be routable in the "e2e" environment.');
        self::assertSame('/api/auth/e2e/login', $route->getPath());
        self::assertSame(['POST'], $route->getMethods());
    }

    /** @return iterable<string, array{string}> */
    public static function environmentsWithoutTheRoute(): iterable
    {
        yield 'prod' => ['prod'];
        yield 'dev' => ['dev'];
        yield 'test' => ['test'];
    }

    private function routesFor(string $environment): RouteCollection
    {
        $kernel = new Kernel($environment, $environment !== 'prod');
        $kernel->boot();

        $router = $kernel->getContainer()->get('router');
        self::assertInstanceOf(RouterInterface::class, $router);

        $routes = $router->getRouteCollection();
        $kernel->shutdown();

        return $routes;
    }
}
