<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Home;

use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeCardRegistry;
use Maggie\Core\Home\HomeDay;
use PHPUnit\Framework\TestCase;

class HomeCardRegistryTest extends TestCase
{
    private static function provider(string $module): HomeCardProviderInterface
    {
        return new class($module) implements HomeCardProviderInterface {
            public function __construct(private readonly string $module)
            {
            }

            public function module(): string
            {
                return $this->module;
            }

            public function state(User $user, HomeDay $day): array
            {
                return [];
            }
        };
    }

    public function testEmptyRegistry(): void
    {
        $registry = new HomeCardRegistry([]);

        self::assertSame([], $registry->all());
        self::assertNull($registry->get('tasks'));
    }

    public function testProvidersKeepTheirRegistrationOrder(): void
    {
        $registry = new HomeCardRegistry(array_map(self::provider(...), ['leisure', 'extra', 'agenda', 'tasks', 'grocery']));

        self::assertSame(
            ['leisure', 'extra', 'agenda', 'tasks', 'grocery'],
            array_map(static fn (HomeCardProviderInterface $p): string => $p->module(), $registry->all()),
        );
    }

    public function testLookupBySlug(): void
    {
        $agenda = self::provider('agenda');
        $registry = new HomeCardRegistry([self::provider('tasks'), $agenda]);

        self::assertSame($agenda, $registry->get('agenda'));
        self::assertNull($registry->get('finance'));
    }
}
