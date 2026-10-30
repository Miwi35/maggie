<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The profile's tools are reached only with a bearer, like every MCP tool (MAG-707). */
class HealthProfileAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function tools(): iterable
    {
        yield 'get_health_profile' => ['get_health_profile', []];
        yield 'update_health_profile' => ['update_health_profile', ['stepGoal' => 10000]];
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
            'params' => ['name' => $tool, 'arguments' => (object) $arguments],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
