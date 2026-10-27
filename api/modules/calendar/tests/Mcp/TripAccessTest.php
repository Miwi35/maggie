<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** set_trip and list_trips are reached only with a bearer, like every MCP tool (MAG-654). */
class TripAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function tools(): iterable
    {
        yield 'set_trip' => ['set_trip', ['eventId' => '01J0000000000000000000000A', 'mode' => 'drive', 'origin' => 'position']];
        yield 'list_trips' => ['list_trips', ['date' => '2026-11-03']];
    }

    /** @param array<string, mixed> $arguments */
    #[DataProvider('tools')]
    public function testWithoutABearerTheCallIsRefusedWith401(string $tool, array $arguments): void
    {
        $client = self::createClient();

        $client->request('POST', '/_mcp', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json, text/event-stream',
        ], content: json_encode([
            'jsonrpc' => '2.0',
            'id' => 1,
            'method' => 'tools/call',
            'params' => ['name' => $tool, 'arguments' => $arguments],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
