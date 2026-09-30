<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use App\Kernel;
use Maggie\Core\E2e\Controller\E2eLoginController;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Psr\Container\ContainerInterface;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Component\Routing\RouteCollection;
use Symfony\Component\Routing\RouterInterface;

/**
 * MAG-94 asks for the e2e-only surface's absence from production to be tested
 * explicitly, not asserted in prose. This is that test.
 *
 * It boots a real kernel per environment and asks the router and the console
 * application, because those are what actually decide whether anything can
 * reach the code. A test that merely checked a class annotation would still
 * pass the day someone adds the route to a config file.
 *
 * The seed command matters at least as much as the login: it issues
 * `TRUNCATE TABLE <every table> RESTART IDENTITY CASCADE`, and it has no route
 * to be absent from — only its service registration keeps it out.
 */
final class E2eSurfaceAbsenceTest extends TestCase
{
    private const ROUTE = 'auth_e2e_login';
    private const COMMAND = 'app:e2e:seed';

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

    #[DataProvider('environmentsWithoutTheRoute')]
    public function testSeedCommandIsNotRegisteredOutsideE2e(string $environment): void
    {
        $kernel = new Kernel($environment, $environment !== 'prod');
        $kernel->boot();

        $names = array_keys((new Application($kernel))->all());
        $kernel->shutdown();

        self::assertNotContains(
            self::COMMAND,
            $names,
            sprintf('app:e2e:seed truncates every table; it must not exist in the "%s" environment.', $environment),
        );
    }

    public function testSeedCommandIsRegisteredInE2e(): void
    {
        $kernel = new Kernel('e2e', true);
        $kernel->boot();

        $names = array_keys((new Application($kernel))->all());
        $kernel->shutdown();

        self::assertContains(self::COMMAND, $names);
    }

    #[DataProvider('environmentsWithoutTheRoute')]
    public function testTheLoginControllerIsNotAServiceOutsideE2e(string $environment): void
    {
        self::assertFalse(
            $this->containerFor($environment)->has(E2eLoginController::class),
            sprintf('E2eLoginController must not be a service in the "%s" environment.', $environment),
        );
    }

    public function testTheLoginControllerIsAServiceInE2e(): void
    {
        // The mirror that keeps the assertion above honest. It works only
        // because controllers are made public by the
        // `controller.service_arguments` pass — `Container::has()` answers
        // false for any private id, so the same check on E2eSeedCommand would
        // pass in `e2e` too and prove nothing. That command's absence is
        // covered by the console-application cases instead.
        self::assertTrue($this->containerFor('e2e')->has(E2eLoginController::class));
    }

    /** @return iterable<string, array{string}> */
    public static function environmentsWithoutTheRoute(): iterable
    {
        yield 'prod' => ['prod'];
        yield 'dev' => ['dev'];
        yield 'test' => ['test'];
    }

    private function containerFor(string $environment): ContainerInterface
    {
        $kernel = new Kernel($environment, $environment !== 'prod');
        $kernel->boot();

        // The container object outlives the kernel; shutting down keeps each
        // environment from holding one open for the rest of the suite.
        $container = $kernel->getContainer();
        $kernel->shutdown();

        return $container;
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
