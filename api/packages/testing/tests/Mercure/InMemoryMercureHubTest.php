<?php

declare(strict_types=1);

namespace PsychedLabs\Testing\Tests\Mercure;

use PHPUnit\Framework\TestCase;
use PsychedLabs\Testing\Mercure\InMemoryMercureHub;
use Symfony\Component\Mercure\Update;

final class InMemoryMercureHubTest extends TestCase
{
    public function testItKeepsPublishedUpdatesInOrderUntilReset(): void
    {
        $hub = new InMemoryMercureHub();
        $first = new Update('/a', '{}');
        $second = new Update('/b', '{}');

        self::assertStringStartsWith('urn:uuid:', $hub->publish($first));
        $hub->publish($second);

        self::assertSame([$first, $second], $hub->getUpdates());

        $hub->reset();

        self::assertSame([], $hub->getUpdates());
    }
}
